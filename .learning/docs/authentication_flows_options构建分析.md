# `/api/v1/authentication_flows` 返回 `action.data.options` 的构建分析

## 1. 入口：创建认证流程接口

接口：

```http
POST /api/v1/authentication_flows
Content-Type: application/json

{
  "type": "login",
  "name": "default"
}
```

在 handler 中，`result` 来自 `output.ToFlowResponse()`：

```go
// pkg/auth/handler/api/authenticationflow_v1_create.go
func (h *AuthenticationFlowV1CreateHandler) create(ctx context.Context, w http.ResponseWriter, r *http.Request, request AuthenticationFlowV1NonRestfulCreateRequest) {
	output, err := h.create0(ctx, w, r, request)
	if err != nil {
		httputil.WriteJSONResponse(ctx, w, &api.Response{Error: err})
		return
	}

	result := output.ToFlowResponse()
	httputil.WriteJSONResponse(ctx, w, &api.Response{Result: result})
}
```

`create0` 中实例化 flow 并调用 `CreateNewFlow`：

```go
// pkg/auth/handler/api/authenticationflow_v1_create.go
func (h *AuthenticationFlowV1CreateHandler) create0(ctx context.Context, w http.ResponseWriter, r *http.Request, request AuthenticationFlowV1NonRestfulCreateRequest) (*authflow.ServiceOutput, error) {
	flow, err := authflow.InstantiateFlow(*request.GetFlowReference(), jsonpointer.T{})
	if err != nil {
		return nil, err
	}

	output, err := h.Workflows.CreateNewFlow(ctx, flow, sessionOptions)
	if err != nil {
		return nil, err
	}
	return output, nil
}
```

---

## 2. `result.action` 从哪里来

`ToFlowResponse()` 直接把 `ServiceOutput.FlowAction` 写到响应里：

```go
// pkg/lib/authenticationflow/service.go
func (o *ServiceOutput) ToFlowResponse() FlowResponse {
	return FlowResponse{
		StateToken: o.Flow.StateToken,
		Type:       o.FlowReference.Type,
		Name:       o.FlowReference.Name,
		Action:     o.FlowAction,
	}
}
```

而 `FlowAction` 由 `Service.getFlowAction()` 计算。关键点是：如果当前 InputReactor 实现了 `DataOutputer`，会调用 `OutputData()`，其结果放进 `flowAction.Data`。

```go
// pkg/lib/authenticationflow/service.go
func (s *Service) getFlowAction(ctx context.Context, session *Session, flow *Flow) (flowAction *FlowAction, err error) {
	findInputReactorResult, err := FindInputReactor(ctx, s.Deps, NewFlows(flow))
	if err != nil {
		return nil, err
	}

	var data Data
	if dataOutputer, ok := findInputReactorResult.InputReactor.(DataOutputer); ok {
		data, err = dataOutputer.OutputData(ctx, s.Deps, findInputReactorResult.Flows)
		if err != nil {
			return nil, err
		}
	}
	if data == nil {
		data = mapData{}
	}
	if flowAction != nil {
		flowAction.Data = data
	}
	return
}
```

---

## 3. identify 步骤如何产出 `identification_data.options`

当前请求是 `login/default`，进入登录 flow 的 identify step 时，InputReactor 是 `IntentLoginFlowStepIdentify`，它实现了 `DataOutputer`：

```go
// pkg/lib/authenticationflow/declarative/intent_login_flow_step_identify.go
func (i *IntentLoginFlowStepIdentify) OutputData(ctx context.Context, deps *authflow.Dependencies, flows authflow.Flows) (authflow.Data, error) {
	return NewIdentificationData(IdentificationData{
		Options: i.Options,
	}), nil
}
```

这里 `options` 的真正来源是 `i.Options`。`i.Options` 在 `NewIntentLoginFlowStepIdentify(...)` 中构建：

```go
// pkg/lib/authenticationflow/declarative/intent_login_flow_step_identify.go
options := []IdentificationOption{}
for _, b := range step.OneOf {
	switch b.Identification {
	case model.AuthenticationFlowIdentificationEmail,
		model.AuthenticationFlowIdentificationPhone,
		model.AuthenticationFlowIdentificationUsername:
		c := NewIdentificationOptionLoginID(flows, b.Identification, b.BotProtection, deps.Config.BotProtection)
		options = append(options, c)

	case model.AuthenticationFlowIdentificationOAuth:
		oauthOptions := NewIdentificationOptionsOAuth(
			flows,
			deps.Config.Identity.OAuth,
			deps.FeatureConfig.Identity.OAuth.Providers,
			b.BotProtection,
			deps.Config.BotProtection,
			deps.SSOOAuthDemoCredentials,
		)
		options = append(options, oauthOptions...)

	case model.AuthenticationFlowIdentificationPasskey:
		requestOptions, err := deps.PasskeyRequestOptionsService.MakeModalRequestOptions(ctx)
		if err != nil {
			return nil, err
		}
		c := NewIdentificationOptionPasskey(flows, requestOptions, b.BotProtection, deps.Config.BotProtection)
		options = append(options, c)

	case model.AuthenticationFlowIdentificationLDAP:
		ldapOptions := NewIdentificationOptionLDAP(deps.Config.Identity.LDAP, b.BotProtection, deps.Config.BotProtection)
		options = append(options, ldapOptions...)

	case model.AuthenticationFlowIdentificationIDToken:
		c := NewIdentificationOptionIDToken(flows, b.Identification, nil, deps.Config.BotProtection)
		options = append(options, c)
	}
}
i.Options = options
```

结论：

- `email/phone/username`：每个分支生成一个 option。
- `oauth`：会展开成多个 option（每个 provider 一个）。
- `passkey`：一个 option（带 `request_options`）。
- `ldap`：每个 LDAP server 一个 option。
- `id_token`：一个 option。

---

## 4. OAuth option 为什么会有 `alias` 和 provider 信息

OAuth 的展开逻辑在 `NewIdentificationOptionsOAuth`：

```go
// pkg/lib/authenticationflow/declarative/data_identification.go
func NewIdentificationOptionsOAuth(...) []IdentificationOption {
	output := []IdentificationOption{}
	for _, p := range oauthConfig.Providers {
		if !identity.IsOAuthSSOProviderTypeDisabled(p.AsProviderConfig(), oauthFeatureConfig) {
			status := p.ComputeProviderStatus(demoCredentials)
			output = append(output, IdentificationOption{
				Identification: model.AuthenticationFlowIdentificationOAuth,
				BotProtection:  GetBotProtectionData(flows, authflowCfg, appCfg),
				ProviderType:   p.AsProviderConfig().Type(),
				Alias:          p.Alias(),
				WechatAppType:  wechat.ProviderConfig(p).AppType(),
				ProviderStatus: status,
			})
		}
	}
	return output
}
```

因此如果配置里有 Google provider，返回中会出现类似：

- `identification: "oauth"`
- `alias: "google"`
- provider 类型字段（当前代码结构体字段名是 `provider_type`）

---

## 5. `default` login flow 的 `oneOf` 来源

当请求 `type=login,name=default` 且未命中显式配置 flow 时，会走 generated flow：

```go
// pkg/lib/authenticationflow/declarative/utils_common.go
func flowRootObjectForLoginFlow(cfg *config.AppConfig, flowReference authflow.FlowReference) (config.AuthenticationFlowObject, error) {
	var root config.AuthenticationFlowObject
	for _, f := range cfg.AuthenticationFlow.LoginFlows {
		if f.Name == flowReference.Name {
			root = f
			break
		}
	}
	if root == nil && flowReference.Name == nameGeneratedFlow {
		root = GenerateLoginFlowConfig(cfg)
	}
	if root == nil {
		return nil, ErrFlowNotFound
	}
	return root, nil
}
```

`GenerateLoginFlowConfig` 会构建 identify step 的 `oneOf`，其顺序再影响最终 `options` 顺序：

```go
// pkg/lib/authenticationflow/declarative/generate_config_login_flow.go
func generateLoginFlowStepIdentify(cfg *config.AppConfig) *config.AuthenticationFlowLoginFlowStep {
	step := &config.AuthenticationFlowLoginFlowStep{
		Name: nameStepIdentify(config.AuthenticationFlowTypeLogin),
		Type: config.AuthenticationFlowLoginFlowStepTypeIdentify,
	}

	for _, identityType := range cfg.Authentication.Identities {
		switch identityType {
		case model.IdentityTypeLoginID:
			step.OneOf = append(step.OneOf, generateLoginFlowStepIdentifyLoginID(cfg)...)
		case model.IdentityTypeOAuth:
			step.OneOf = append(step.OneOf, generateLoginFlowStepIdentifyOAuth(cfg)...)
		case model.IdentityTypePasskey:
			step.OneOf = append(step.OneOf, generateLoginFlowStepIdentifyPasskey(cfg)...)
		case model.IdentityTypeLDAP:
			step.OneOf = append(step.OneOf, generateLoginFlowStepIdentifyLDAP(cfg)...)
		}
	}
	return step
}
```

---

## 6. 对样例响应的对应关系

样例：

```json
{
  "action": {
    "type": "identify",
    "data": {
      "type": "identification_data",
      "options": [
        { "identification": "oauth", "alias": "google", "oauth_provider_type": "google" },
        { "identification": "email" },
        { "identification": "username" }
      ]
    }
  }
}
```

可映射为：

1. 当前 action 在 identify step；
2. `OutputData()` 输出 `IdentificationData{Options: i.Options}`；
3. `i.Options` 由 identify step 的 `oneOf` 遍历生成；
4. `oauth` 分支按 provider 展开（出现 `google` alias）；
5. `email`/`username` 各自生成一个 option。

> 备注：当前代码 `IdentificationOption` 对应字段名是 `provider_type`。若响应中看到 `oauth_provider_type`，通常是历史版本或其他数据结构（如 OAuthData）中的字段命名差异。

---

## 7. `feedInput` 中 Context 与 Session 的作用区别

这里两者是两层职责：

- `context.Context`：一次调用链路的执行上下文（取消、超时、trace/log、依赖读取上下文值）。
- `*Session`：认证流程的持久化会话状态（跨请求保存、可更新、可删除）。

### 7.1 Session 先被取出，再注入到 Context

```go
// pkg/lib/authenticationflow/service.go
func (s *Service) getSessionAndUpdateContext(ctx context.Context, flowID string) (context.Context, *Session, error) {
	session, err := s.Store.GetSession(ctx, flowID)
	if err != nil {
		return ctx, nil, err
	}

	ctx = session.MakeContext(ctx, s.Deps)
	return ctx, session, nil
}
```

含义：

1. 从 store 读取会话实体（`session`）；
2. 将 session 相关值灌入 `ctx`，供后续流程执行使用。

### 7.2 feedInput 中 Context 的作用

`ctx` 被贯穿传递给流程执行的所有核心步骤：读取 flow、事务、run-effects、accept 输入、计算下一步 action。

```go
// pkg/lib/authenticationflow/service.go
func (s *Service) feedInput(ctx context.Context, session *Session, stateToken string, rawMessage json.RawMessage) (flow *Flow, flowAction *FlowAction, err error) {
	flow, err = s.Store.GetFlowByStateToken(ctx, stateToken)
	if err != nil {
		return
	}

	err = s.Database.ReadOnly(ctx, func(ctx context.Context) error {
		err = ApplyRunEffects(ctx, s.Deps, flows)
		if err != nil {
			return err
		}

		err = Accept(ctx, s.Deps, flows, acceptResult, rawMessage)
		if err != nil && !errors.Is(err, ErrEOF) {
			return err
		}

		flowAction, err = s.getFlowAction(ctx, session, flow)
		return err
	})
	// ...
}
```

所以 `Context` 更像“执行管道”，保证本次调用中的环境信息一致传播。

### 7.3 feedInput 中 Session 的作用

`session` 是业务状态载体，典型用途：

- 写入并持久化流程状态（例如 bot protection 验证结果）；
- 参与生成输出（`session.ToOutput()`）；
- flow 完成时删除会话（`DeleteSession`）。

关键代码：

```go
// pkg/lib/authenticationflow/service.go
func (s *Service) processAcceptResult(
	ctx context.Context,
	session *Session,
	flows Flows,
	acceptResult *AcceptResult,
) error {
	if acceptResult.BotProtectionVerificationResult != nil {
		session.SetBotProtectionVerificationResult(acceptResult.BotProtectionVerificationResult)
		updateSessionErr := s.Store.UpdateSession(ctx, session)
		if updateSessionErr != nil {
			return updateSessionErr
		}
	}
	// ...
	return nil
}
```

### 7.4 一句话总结

- `Context` 解决“这次怎么执行”（运行时传播）。
- `Session` 解决“这个 flow 目前是什么状态”（业务状态持久化）。
