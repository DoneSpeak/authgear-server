# Authgear Rate Limit 机制说明

本文档说明 Authgear 中 Rate Limit（限流）的实现机制、响应格式及常见使用场景。

---

## 一、概述

Authgear 使用 **Token Bucket 算法**实现限流，通过 `pkg/lib/ratelimit` 包提供。限流可以作用于：

- **全局**：跨所有租户的全局限制（如全局 SMS 发送限制）。
- **租户级别**：每个应用（App）独立的限制。
- **维度**：Per IP、Per User、Per Target（目标手机号/邮箱）等。

---

## 二、核心类型与结构

| 类型 | 所在文件 | 说明 |
|------|----------|------|
| `RateLimited`（Reason） | `pkg/lib/ratelimit/error.go` | `apierrors.TooManyRequest.WithReason("RateLimited")`，表示限流错误 |
| `ErrRateLimited()` | `pkg/lib/ratelimit/error.go` | 构造限流错误，返回 `*APIError`，包含限流信息 |
| `RateLimit`（Model） | `pkg/api/model/ratelimit.go` | `{ Name: string, Group: string }`，标识具体的限流规则 |
| `BucketSpec` | `pkg/lib/ratelimit/*.go` | 限流配置：Period（周期）、Burst（突发上限）、Key（维度如 IP） |
| `Reservation` | `pkg/lib/ratelimit/reservation.go` | 成功的令牌预留 |
| `FailedReservation` | `pkg/lib/ratelimit/reservation.go` | 失败的令牌预留，包含 `timeToAct`（可重试时间） |
| `RateLimitMiddleware` | `pkg/lib/authenticationflow/rate_limit_middleware.go`、`pkg/lib/accountmanagement/rate_limit_middleware.go` | HTTP 中间件，自动拦截超限请求 |

---

## 三、API 响应格式

### 3.1 完整响应 JSON

当请求触发 Rate Limit 时，HTTP 状态码为 **429 Too Many Requests**，Body 格式为标准 API 响应：

```json
{
  "error": {
    "name": "TooManyRequest",
    "reason": "RateLimited",
    "message": "request rate limited",
    "code": 429,
    "info": {
      "rate_limit": {
        "name": "authentication_password_per_ip",
        "group": "authentication"
      },
      "bucket_name": "AuthflowAPIPerIP"
    }
  }
}
```

### 3.2 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | `string` | 固定为 `"TooManyRequest"` |
| `reason` | `string` | 固定为 `"RateLimited"` |
| `message` | `string` | 调试信息，固定为 `"request rate limited"` |
| `code` | `number` | HTTP 状态码，固定为 `429` |
| `info.rate_limit.name` | `string` | 限流规则名称，如 `authentication_password_per_ip` |
| `info.rate_limit.group` | `string` | 限流规则分组，如 `authentication`、`messaging` |
| `info.bucket_name` | `string` | 桶名称（已废弃，推荐使用 `rate_limit`） |

### 3.3 简化响应（Middleware 场景）

部分 Middleware 使用简化构造，响应中可能只包含基本字段：

```json
{
  "error": {
    "name": "TooManyRequest",
    "reason": "ReachRateLimit",
    "message": "Reach Rate Limit",
    "code": 429
  }
}
```

---

## 四、代码实现

### 4.1 限流错误构造

**文件**：`pkg/lib/ratelimit/error.go`

```go
var RateLimited = apierrors.TooManyRequest.WithReason("RateLimited")

func ErrRateLimited(rl RateLimitName, rlgroup RateLimitGroup, bucketName BucketName) error {
    details := apierrors.Details{
        DEPRECATED_bucketNameKey: bucketName,
    }
    if rl != "" {
        details[rateLimitKey] = model.RateLimit{
            Name:  string(rl),
            Group: string(rlgroup),
        }
        details[DEPRECATED_rateLimitNameKey] = rlgroup
    }
    return RateLimited.NewWithInfo("request rate limited", details)
}
```

### 4.2 中间件实现（Authentication Flow）

**文件**：`pkg/lib/authenticationflow/rate_limit_middleware.go`

```go
func (m *RateLimitMiddleware) Handle(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        ctx := r.Context()
        spec := ratelimit.NewBucketSpec(
            "", "",
            m.Config.AuthenticationFlow.RateLimits.PerIP,
            AuthowAPIPerIP,
            string(m.RemoteIP),
        )
        failedReservation, err := m.RateLimiter.Allow(ctx, spec)
        if err != nil {
            panic(err)
        } else if ratelimitErr := failedReservation.Error(); ratelimitErr != nil && ratelimit.IsRateLimitErrorWithBucketName(ratelimitErr, spec.Name) {
            httputil.WriteJSONResponse(ctx, w, &api.Response{
                Error: apierrors.NewTooManyRequest("Reach Rate Limit"),
            })
        } else {
            next.ServeHTTP(w, r)
        }
    })
}
```

### 4.3 中间件实现（Account Management）

**文件**：`pkg/lib/accountmanagement/rate_limit_middleware.go`

```go
func (m *RateLimitMiddleware) Handle(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        ctx := r.Context()
        spec := ratelimit.NewBucketSpec(
            "", "",
            accountManagementAPIPerIPConfig,
            AccountManagementAPIPerIP,
            string(m.RemoteIP),
        )
        failed, err := m.RateLimiter.Allow(ctx, spec)
        if err != nil {
            panic(err)
        } else if ratelimitErr := failed.Error(); ratelimitErr != nil && ratelimit.IsRateLimitErrorWithBucketName(err, spec.Name) {
            httputil.WriteJSONResponse(ctx, w, &api.Response{
                Error: apierrors.NewTooManyRequest("Reach Rate Limit"),
            })
        } else {
            next.ServeHTTP(w, r)
        }
    })
}
```

### 4.4 失败预留与时间信息

**文件**：`pkg/lib/ratelimit/reservation.go`

```go
type FailedReservation struct {
    key       string
    spec      BucketSpec
    timeToAct time.Time
}

func (r *FailedReservation) Error() error {
    if r == nil {
        return nil
    }
    return ErrRateLimited(r.spec.RateLimitName, r.spec.RateLimitGroup, r.spec.Name)
}

func (r *FailedReservation) GetTimeToAct() time.Time {
    if r == nil {
        return time.Unix(0, 0).UTC()
    }
    return r.timeToAct
}
```

`timeToAct` 表示最早可重试时间，但当前 **API 响应中不包含 Retry-After 头或字段**（代码中没有显式输出）。

---

## 五、常见限流规则示例

| 场景 | 限流维度 | 示例配置（Period / Burst） |
|------|----------|---------------------------|
| 密码登录 | Per IP | 1m / 20 |
| 密码登录 | Per User + Per IP | 1m / 5 |
| 发送邮件验证码 | Per IP | 1m / 10 |
| 发送邮件验证码 | Per User | 1m / 3 |
| 发送短信验证码 | Per IP | 1m / 20 |
| 发送短信验证码 | Per Target | 1m / 30 |
| 验证邮件验证码 | Per IP | 1m / 100 |
| 验证短信验证码 | Per IP | 1m / 30 |
| 账户遍历攻击防护 | Per IP | 1m / 5 |
| Auth Flow API | Per IP | 1m / 1200 |
| Account Management API | Per IP | 1m / 1200 |

---

## 六、客户端处理建议

### 6.1 检测限流错误

```typescript
function isRateLimitError(error: APIError): boolean {
  return error.name === "TooManyRequest" && error.reason === "RateLimited";
}
```

### 6.2 建议的重试策略

- **立即重试**：不推荐，可能继续被限流。
- **指数退避**：首次等待 1s，二次 2s，三次 4s，以此类推。
- **最大重试次数**：如 5 次后放弃。

```typescript
async function withRateLimitRetry<T>(
  request: () => Promise<T>,
  maxRetries: number = 5
): Promise<T> {
  let lastError: Error | null = null;
  for (let i = 0; i < maxRetries; i++) {
    try {
      return await request();
    } catch (e) {
      const apiError = e as APIError;
      if (isRateLimitError(apiError)) {
        lastError = e;
        const waitMs = Math.pow(2, i) * 1000;
        await new Promise(r => setTimeout(r, waitMs));
        continue;
      }
      throw e;
    }
  }
  throw lastError;
}
```

### 6.3 注意事项

- **不要依赖 `message` 判断**：`message` 是调试信息，可能变化，应基于 `name` 和 `reason` 判断。
- **多个限流规则可能同时触发**：如同时触发 Per IP 和 Per User 限制，会返回其中一个。
- **429 之外也可能是限流**：部分内部组件可能返回其他状态码，但标准 API 一律返回 429。

---

## 七、相关文件索引

| 文件路径 | 说明 |
|----------|------|
| `pkg/lib/ratelimit/error.go` | `RateLimited` Reason、`ErrRateLimited` 函数 |
| `pkg/lib/ratelimit/reservation.go` | `Reservation`、`FailedReservation` |
| `pkg/lib/ratelimit/limiter.go` | Token Bucket 算法实现 |
| `pkg/lib/ratelimit/*.go` | 其他限流相关类型 |
| `pkg/lib/authenticationflow/rate_limit_middleware.go` | Auth Flow 限流中间件 |
| `pkg/lib/accountmanagement/rate_limit_middleware.go` | Account Management 限流中间件 |
| `pkg/api/model/ratelimit.go` | `RateLimit` Model |
| `pkg/api/apierrors/kinds.go` | `TooManyRequest` Name 定义 |
| `pkg/api/apierrors/error.go` | `NewTooManyRequest` 等工具函数 |
| `pkg/lib/usage/errors.go` | `UsageLimitExceeded`（用量超限，与 Rate Limit 不同） |
| `pkg/lib/lockout/error.go` | `AccountLockout`（账户锁定，与 Rate Limit 不同） |

---

## 八、与类似概念的区别

| 概念 | 说明 | 触发条件 |
|------|------|----------|
| **Rate Limit** | 频率限制 | 请求过于频繁 |
| **Account Lockout** | 账户锁定 | 多次验证失败（如密码错误） |
| **Usage Limit** | 用量限制 | 超过配额（如每月 SMS 限额） |
| **Bot Protection** | 机器人防护 | 行为检测异常 |

---

## 九、参考

- 错误处理规范：`docs/error-handling.md`
- API 错误响应格式：`.learning/docs/Authgear异常码与Reason统计.md`
- Token Bucket 算法说明：`pkg/lib/ratelimit/limiter.go`
