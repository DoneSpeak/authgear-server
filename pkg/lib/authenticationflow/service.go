// authenticationflow 包负责处理认证流程（Authentication Flow）
//
// 什么是认证流程？想象一下你在手机 App 上登录的过程：
// 1. 输入手机号
// 2. 收到验证码
// 3. 输入验证码
// 4. 登录成功
//
// 这个包就是用来管理和执行这样的多步骤认证流程的。它支持多种认证方式：
// - 登录（Login）
// - 注册（Signup）
// - 提升权限（Promote）
// - 二次验证（Reauth）
// - 账号恢复（Account Recovery）
//
// 核心概念：
// - Flow（流程）：一个完整的认证过程，如登录流程
// - Node（节点）：流程中的一个步骤，如输入密码
// - Session（会话）：用户的交互会话，跨越多个请求
// - State Token（状态令牌）：标识当前流程状态的令牌
package authenticationflow

import (
	"context"       // Go 标准库：上下文，用于传递请求范围和取消信号
	"encoding/json" // Go 标准库：JSON 处理
	"errors"        // Go 标准库：错误处理
	"net/http"      // Go 标准库：HTTP 相关的类型，如 Cookie

	"github.com/iawaknahc/jsonschema/pkg/jsonpointer" // JSON 指针处理库

	// 项目内部依赖包
	"github.com/authgear/authgear-server/pkg/lib/authn/authenticationinfo" // 认证信息
	"github.com/authgear/authgear-server/pkg/lib/config"                   // 配置
	"github.com/authgear/authgear-server/pkg/lib/oauth/oauthsession"       // OAuth 会话
	"github.com/authgear/authgear-server/pkg/lib/otelauthgear"             // OpenTelemetry 指标
	"github.com/authgear/authgear-server/pkg/util/otelutil"                // OpenTelemetry 工具
	"github.com/authgear/authgear-server/pkg/util/slogutil"                // 结构化日志工具
)

//go:generate go tool mockgen -source=service.go -destination=service_mock_test.go -package authenticationflow

// ServiceOutput 表示服务层方法的输出结果
// 这是一个聚合结构体，包含了流程执行后的所有相关信息
//
// 字段说明：
//   - Session: 用户会话对象，包含用户的会话信息
//   - SessionOutput: 会话的输出表示，用于返回给客户端
//   - Flow: 当前的认证流程对象
//   - FlowReference: 流程引用，标识流程类型和名称
//   - Finished: 流程是否已结束
//   - FlowAction: 当前流程的动作，告诉客户端下一步该做什么
//   - Cookies: 需要设置到浏览器的 Cookie 列表
type ServiceOutput struct {
	Session       *Session
	SessionOutput *SessionOutput
	Flow          *Flow

	FlowReference *FlowReference
	Finished      bool
	FlowAction    *FlowAction

	Cookies []*http.Cookie
}

// ToFlowResponse 将 ServiceOutput 转换为 FlowResponse
// FlowResponse 是返回给客户端的 API 响应格式
//
// 为什么需要转换？
// ServiceOutput 包含内部使用的完整对象（如 Session、Flow 指针），
// 而 FlowResponse 只包含客户端需要的信息（如 StateToken、Action）
func (o *ServiceOutput) ToFlowResponse() FlowResponse {
	return FlowResponse{
		StateToken: o.Flow.StateToken,    // 状态令牌，客户端用这个来标识当前流程
		Type:       o.FlowReference.Type, // 流程类型，如 "login"、"signup"
		Name:       o.FlowReference.Name, // 流程名称
		Action:     o.FlowAction,         // 当前动作，告诉客户端下一步做什么
	}
}

// ServiceLogger 是 authenticationflow 包的全局日志记录器
// 使用 "authenticationflow-service" 作为日志标识符
// 这样可以在日志中区分来自这个包的日志
var ServiceLogger = slogutil.NewLogger("authenticationflow-service")

// Store 定义了认证流程存储层的接口
// 这是"依赖倒置原则"的应用：服务层不依赖具体的存储实现，而是依赖接口
//
// 什么是依赖倒置？
// 传统方式：高层模块（服务层）直接依赖低层模块（Redis 实现）
// 倒置方式：高层和低层都依赖抽象（接口），低层模块实现接口
// 好处：可以轻松切换存储实现（如从 Redis 切换到数据库）
//
// 方法分为两组：
// 1. Session 相关：管理用户会话
// 2. Flow 相关：管理认证流程
// Store 接口方法定义
// Session 相关：
//   - CreateSession: 创建新会话
//   - GetSession: 根据 flowID 获取会话
//   - DeleteSession: 删除会话
//   - UpdateSession: 更新会话
// Flow 相关：
//   - CreateFlow: 创建/保存流程（实际上是"保存或更新"，因为会覆盖旧的）
//   - GetFlowByStateToken: 根据状态令牌获取流程
//   - DeleteFlow: 删除流程
type Store interface {
	CreateSession(ctx context.Context, session *Session) error
	GetSession(ctx context.Context, flowID string) (*Session, error)
	DeleteSession(ctx context.Context, session *Session) error
	UpdateSession(ctx context.Context, session *Session) error

	CreateFlow(ctx context.Context, flow *Flow) error
	GetFlowByStateToken(ctx context.Context, stateToken string) (*Flow, error)
	DeleteFlow(ctx context.Context, flow *Flow) error
}

// ServiceDatabase 定义了数据库事务接口
// 同样是依赖倒置原则的应用
//
// WithTx: 在事务中执行操作
//   - 如果 do 函数返回错误，事务会回滚
//   - 如果 do 函数成功，事务会提交
//
// ReadOnly: 在只读事务中执行操作
//   - 用于只需要查询的场景，性能更好
//   - 不能执行写入操作
type ServiceDatabase interface {
	WithTx(ctx context.Context, do func(ctx context.Context) error) (err error)
	ReadOnly(ctx context.Context, do func(ctx context.Context) error) (err error)
}

// ServiceUIInfoResolver 用于解析 UI 相关信息
// 例如：在认证完成后，需要在重定向 URL 中添加认证信息
//
// SetAuthenticationInfoInQuery: 将认证信息添加到 URL 查询参数中
// 这样前端页面可以读取这些信息，如登录成功后的用户 ID
type ServiceUIInfoResolver interface {
	SetAuthenticationInfoInQuery(redirectURI string, e *authenticationinfo.Entry) string
}

// OAuthClientResolver 用于解析 OAuth 客户端配置
// OAuth 客户端代表第三方应用，如"使用 Google 登录"中的 Google
//
// ResolveClient: 根据 clientID 获取客户端配置
// 用于检查客户端是否有权限执行某些操作
type OAuthClientResolver interface {
	ResolveClient(clientID string) *config.OAuthClientConfig
}

// OAuthSessionStore 用于存储 OAuth 会话
// OAuth 会话保存了 OAuth 授权过程中的临时状态
//
// Get: 获取会话条目
// Save: 保存会话条目
type OAuthSessionStore interface {
	Get(ctx context.Context, entryID string) (entry *oauthsession.Entry, err error)
	Save(ctx context.Context, entry *oauthsession.Entry) (err error)
}

// Service 是认证流程的核心服务结构体
// 它组合了所有需要的依赖，实现了完整的认证流程业务逻辑
//
// 字段说明：
//   - Deps: 依赖注入容器，包含各种服务依赖
//   - Store: 存储层接口，用于持久化流程和会话
//   - Database: 数据库事务接口
//   - UIConfig: UI 配置
//   - UIInfoResolver: UI 信息解析器
//   - OAuthClientResolver: OAuth 客户端解析器
//   - OAuthSessionStore: OAuth 会话存储
//
// 设计模式：依赖注入（Dependency Injection）
// 所有依赖都通过字段注入，而不是在方法内硬编码
// 这使得测试时可以轻松 mock 这些依赖
type Service struct {
	Deps                *Dependencies
	Store               Store
	Database            ServiceDatabase
	UIConfig            *config.UIConfig
	UIInfoResolver      ServiceUIInfoResolver
	OAuthClientResolver OAuthClientResolver
	OAuthSessionStore   OAuthSessionStore
}

// CreateNewFlow 创建一个新的认证流程
// 这是认证流程的入口点，用户开始登录/注册时调用
//
// 参数说明：
//   - ctx: 上下文，用于传递请求范围和取消信号
//   - publicFlow: 公共流程定义，包含流程类型和初始配置
//   - sessionOptions: 会话选项，如用户 ID、客户端 ID、重定向 URI 等
//
// 执行流程：
// 1. 验证新流程是否被允许（检查客户端权限）
// 2. 创建新会话
// 3. 将会话信息注入到上下文中
// 4. 保存会话到存储
// 5. 记录会话创建指标（用于监控）
// 6. 创建流程并执行初始步骤
//
// 示例：
//
//	当用户点击"登录"按钮时，前端调用这个函数
//	传入登录流程定义和会话选项（如客户端 ID）
func (s *Service) CreateNewFlow(ctx context.Context, publicFlow PublicFlow, sessionOptions *SessionOptions) (output *ServiceOutput, err error) {
	// 步骤1：验证流程是否被允许
	// 检查 OAuth 客户端是否有权限创建这个流程
	err = s.validateNewFlow(publicFlow, sessionOptions)
	if err != nil {
		return
	}

	// 步骤2：创建新会话
	// 会话是跨越多个 HTTP 请求的用户状态容器
	session := NewSession(sessionOptions)

	// 步骤3：将会话信息注入上下文
	// 这样后续代码可以通过上下文获取会话信息
	ctx = session.MakeContext(ctx, s.Deps)

	// 步骤4：保存会话到 Redis
	err = s.Store.CreateSession(ctx, session)
	if err != nil {
		return
	}

	// 步骤5：记录监控指标
	// 用于统计有多少会话被创建
	otelutil.IntCounterAddOne(
		ctx,
		otelauthgear.CounterAuthflowSessionCreationCount,
	)

	// 步骤6：创建并执行流程
	return s.createNewFlowWithSession(ctx, publicFlow, session)
}

// validateNewFlow 验证新流程是否被允许创建
//
// 安全考虑：
// 如果提供了 clientID（表示这是第三方应用发起的请求），
// 需要检查该客户端是否有权限执行这个认证流程。
//
// 什么是 Flow Allowlist？
// 它是一种安全机制，限制某些 OAuth 客户端只能使用特定的认证流程。
// 例如：只允许内部应用使用账号恢复流程。
//
// 执行逻辑：
// 1. 检查是否提供了 clientID
// 2. 获取客户端配置
// 3. 创建允许列表检查器
// 4. 验证流程是否在允许列表中
func (s *Service) validateNewFlow(publicFlow PublicFlow, sessionOptions *SessionOptions) (err error) {
	// 如果提供了 clientID，强制执行允许列表检查
	if sessionOptions.ClientID != "" {
		// 获取流程引用（类型+名称）
		flowReference := publicFlow.FlowFlowReference()

		// 解析客户端配置
		client := s.OAuthClientResolver.ResolveClient(sessionOptions.ClientID)
		if client != nil {
			// 创建允许列表检查器
			allowlist := NewFlowAllowlist(client.AuthenticationFlowAllowlist, s.UIConfig.AuthenticationFlow.Groups)

			// 检查流程是否被允许
			if !allowlist.CanCreateFlow(flowReference) {
				// 返回"流程不允许"错误
				return ErrFlowNotAllowed
			}
		}
	}

	return err
}

// createNewFlowWithSession 创建新流程并关联到已有会话
// 这个方法负责创建流程并处理流程的结束逻辑
//
// 执行流程：
// 1. 创建新流程（这会执行流程的初始步骤）
// 2. 检查流程是否已经结束（ErrEOF 表示流程完成）
// 3. 如果流程结束：
//   - 在事务中完成流程（应用效果、收集 Cookie）
//   - 删除会话（清理）
//   - 删除流程（清理）
//
// 4. 构造并返回 ServiceOutput
//
// 什么是 ErrEOF？
// 它不是真正的错误，而是表示"End Of Flow"（流程结束）。
// 某些流程可以一步完成（如某些内部流程），这时会返回 ErrEOF。
func (s *Service) createNewFlowWithSession(ctx context.Context, publicFlow PublicFlow, session *Session) (output *ServiceOutput, err error) {
	var flow *Flow
	var flowAction *FlowAction

	// 步骤1：创建并执行新流程
	flow, flowAction, err = s.createNewFlow(ctx, session, publicFlow)

	// 检查是否是流程结束
	isEOF := errors.Is(err, ErrEOF)
	if err != nil && !isEOF {
		// 真正的错误，直接返回
		return
	}

	// 获取会话的输出表示
	sessionOutput := session.ToOutput()

	// 处理流程结束的情况
	var cookies []*http.Cookie
	if isEOF {
		// 步骤3a：在数据库事务中完成流程
		// 应用所有效果（如创建用户、记录日志）并收集 Cookie
		err = s.Database.WithTx(ctx, func(ctx context.Context) error {
			cookies, err = s.finishFlow(ctx, flow)
			return err
		})
		if err != nil {
			return
		}

		// 步骤3b：删除会话
		err = s.Store.DeleteSession(ctx, session)
		if err != nil {
			return
		}

		// 步骤3c：删除流程
		err = s.Store.DeleteFlow(ctx, flow)
		if err != nil {
			return
		}
	}

	// 如果是 EOF，保留 ErrEOF 错误以便调用者知道流程已结束
	if isEOF {
		err = ErrEOF
	}

	// 步骤4：构造输出
	flowReference := FindCurrentFlowReference(flow)
	output = &ServiceOutput{
		Session:       session,
		SessionOutput: sessionOutput,
		Flow:          flow,
		FlowReference: flowReference,
		FlowAction:    flowAction,
		Cookies:       cookies,
	}
	return
}

// processAcceptResult 处理 Accept 函数执行后的结果
//
// AcceptResult 包含两类需要处理的内容：
// 1. 机器人保护验证结果：需要保存到会话中
// 2. 延迟执行的一次性函数：需要在数据库事务外执行
//
// 为什么需要延迟执行？
// 有些操作（如发送邮件、短信）不应该在只读事务中执行，
// 因为它们可能失败且不应该回滚整个事务。
//
// 错误处理策略：
// 如果延迟执行的函数失败，我们会：
// 1. 尝试恢复数据库状态（重新应用运行时效果）
// 2. 记录认证被阻止的错误（如果需要）
// 3. 包装错误返回
func (s *Service) processAcceptResult(
	ctx context.Context,
	session *Session,
	flows Flows,
	acceptResult *AcceptResult,
) error {
	// 处理机器人保护验证结果
	if acceptResult.BotProtectionVerificationResult != nil {
		// 将验证结果保存到会话
		session.SetBotProtectionVerificationResult(acceptResult.BotProtectionVerificationResult)

		// 更新会话存储
		updateSessionErr := s.Store.UpdateSession(ctx, session)
		if updateSessionErr != nil {
			return updateSessionErr
		}
	}

	// 执行延迟的一次性函数
	for _, fn := range acceptResult.DelayedOneTimeFunctions {
		err := fn(ctx, s.Deps)
		if err != nil {
			// 如果延迟函数失败，进行错误恢复
			err = s.Database.ReadOnly(ctx, func(ctx context.Context) error {
				// 恢复数据库状态：重新应用运行时效果
				runEffectErr := ApplyRunEffects(ctx, s.Deps, flows)
				if runEffectErr != nil {
					// 合并两个错误一起返回
					return errors.Join(runEffectErr, err)
				}

				// 检查是否需要记录认证被阻止的事件
				err = logAuthenticationBlockedErrorIfNeeded(ctx, s.Deps, flows, err)

				// 包装错误添加流程上下文信息
				return newAuthenticationFlowError(flows, err)
			})
			return err
		}
	}
	return nil
}

// createNewFlow 创建新流程并执行其初始步骤
//
// 流程创建的特殊性：
// 新流程创建时没有节点（Nodes），但流程可能配置有"初始动作"。
// 我们通过传入 nil 输入来触发这些初始动作。
//
// 执行流程：
// 1. 创建 Flow 对象
// 2. 循环执行 Accept 直到流程暂停或结束
// 3. 特殊处理 ErrNoChange（某些流程对 nil 输入无反应）
// 4. 保存流程状态
//
// 什么是 shouldAccept 循环？
// 某些情况下，流程处理完输入后需要"暂停并重新接受"（ErrPauseAndRetryAccept），
// 这通常发生在流程内部状态变化需要重新评估时。
func (s *Service) createNewFlow(ctx context.Context, session *Session, publicFlow PublicFlow) (flow *Flow, flowAction *FlowAction, err error) {
	// 步骤1：创建新 Flow 对象
	// FlowID 与会话 ID 相同，建立关联
	flow = NewFlow(session.FlowID, publicFlow)

	// 新流程没有节点，所以不需要应用效果
	// 流程可能配置了初始步骤（如自动跳转），这会在 Accept 中处理

	// 准备 nil 输入来触发流程的初始步骤
	var rawMessage json.RawMessage

	// 步骤2：循环执行流程直到需要用户输入或流程结束
	var shouldAccept = true
	for shouldAccept {
		shouldAccept = false

		// 创建 Flows 包装器
		flows := NewFlows(flow)

		// 创建接受结果对象
		var acceptResult *AcceptResult = NewAcceptResult()

		// 在只读事务中执行 Accept
		err = s.Database.ReadOnly(ctx, func(ctx context.Context) error {
			// 执行流程接受逻辑
			err = Accept(ctx, s.Deps, flows, acceptResult, rawMessage)

			// 检查是否是流程结束
			isEOF := errors.Is(err, ErrEOF)
			if err != nil && !isEOF {
				return err
			}

			// 获取当前流程动作
			flowAction, err = s.getFlowAction(ctx, session, flow)
			if err != nil {
				return err
			}

			// 如果是 EOF，传递这个信号
			if isEOF {
				return ErrEOF
			}
			return nil
		})

		// 处理 Accept 的结果
		acceptErr := s.processAcceptResult(ctx, session, flows, acceptResult)
		if acceptErr != nil {
			return nil, nil, acceptErr
		}

		// 检查是否需要重新执行 Accept
		if errors.Is(err, ErrPauseAndRetryAccept) {
			shouldAccept = true
			err = nil
		}
	}

	// 步骤3：特殊处理 ErrNoChange
	// 新流程不一定能对 nil 输入做出反应，这不是错误
	if errors.Is(err, ErrNoChange) {
		err = nil
	}

	// 检查最终状态
	isEOF := errors.Is(err, ErrEOF)
	if err != nil && !isEOF {
		return
	}

	// 步骤4：持久化流程状态
	// 无论是正常暂停（err == nil）还是流程结束（err == ErrEOF），都需要保存
	err = s.Store.CreateFlow(ctx, flow)
	if err != nil {
		return
	}

	// 保留 EOF 信号
	if isEOF {
		err = ErrEOF
	}
	return
}

// Get 获取当前流程状态
// 当客户端需要刷新流程状态或获取当前步骤时调用
//
// 参数：
//   - stateToken: 状态令牌，标识当前流程状态
//
// 执行流程：
// 1. 根据 stateToken 获取流程
// 2. 获取关联的会话并更新上下文
// 3. 在只读事务中获取流程输出
func (s *Service) Get(ctx context.Context, stateToken string) (output *ServiceOutput, err error) {
	// 步骤1：获取流程
	w, err := s.Store.GetFlowByStateToken(ctx, stateToken)
	if err != nil {
		return
	}

	// 步骤2：获取会话并更新上下文
	ctx, session, err := s.getSessionAndUpdateContext(ctx, w.FlowID)
	if err != nil {
		return
	}

	// 步骤3：在只读事务中获取输出
	err = s.Database.ReadOnly(ctx, func(ctx context.Context) error {
		output, err = s.get(ctx, session, w)
		return err
	})
	return
}

// get 是内部方法，获取流程的当前状态
// 这是 Get 方法的实际实现逻辑
//
// 执行流程：
//  1. 应用运行时效果（RunEffects）
//     效果是指流程中的副作用，如记录日志、发送通知等
//  2. 获取当前流程动作（告诉客户端下一步该做什么）
//  3. 构造输出对象
func (s *Service) get(ctx context.Context, session *Session, w *Flow) (output *ServiceOutput, err error) {
	// 步骤1：应用运行时效果
	// 每次获取流程状态时，都需要先应用待处理的效果
	// 这确保了状态的一致性
	err = ApplyRunEffects(ctx, s.Deps, NewFlows(w))
	if err != nil {
		return
	}

	// 步骤2：获取当前流程动作
	flowAction, err := s.getFlowAction(ctx, session, w)
	if err != nil {
		return
	}

	// 获取会话的输出表示
	sessionOutput := session.ToOutput()

	// 步骤3：构造输出
	flowReference := FindCurrentFlowReference(w)
	output = &ServiceOutput{
		Session:       session,
		SessionOutput: sessionOutput,
		Flow:          w,
		FlowReference: flowReference,
		FlowAction:    flowAction,
	}
	return
}

// FeedInput 向流程提供用户输入
// 这是处理用户提交数据的主要入口点
//
// 参数：
//   - stateToken: 状态令牌（可能为空，此时从输入中解析）
//   - rawMessage: 用户输入的原始 JSON 数据
//
// 执行流程：
// 1. 如果没有提供 stateToken，从输入中解析（特殊场景：账号恢复）
// 2. 获取流程和会话
// 3. 执行 feedInput 处理输入
// 4. 处理特殊错误：
//   - ErrorSwitchFlow: 切换到新流程
//   - ErrorRewriteFlow: 重写当前流程
//
// 5. 如果流程结束（EOF）：
//   - 完成流程（应用效果、收集 Cookie）
//   - 清理会话和流程数据
//
// 6. 构造输出
func (s *Service) FeedInput(ctx context.Context, stateToken string, rawMessage json.RawMessage) (output *ServiceOutput, err error) {
	// 步骤1：如果没有 stateToken，尝试从输入中解析
	// 这用于特殊场景，如账号恢复码验证
	if stateToken == "" {
		stateToken, err = s.resolveStateTokenFromInput(ctx, rawMessage)
		if err != nil {
			return
		}
	}

	// 步骤2：获取流程
	flow, err := s.Store.GetFlowByStateToken(ctx, stateToken)
	if err != nil {
		return
	}

	// 获取会话并更新上下文
	ctx, session, err := s.getSessionAndUpdateContext(ctx, flow.FlowID)
	if err != nil {
		return
	}

	// 步骤3：处理输入
	var flowAction *FlowAction
	flow, flowAction, err = s.feedInput(ctx, session, stateToken, rawMessage)

	// 步骤4：处理特殊错误
	// 某些情况下需要切换或重写流程
	var errSwitchFlow *ErrorSwitchFlow
	var errRewriteFlow *ErrorRewriteFlow
	isSpecialError := false
	for errors.As(err, &errSwitchFlow) || errors.As(err, &errRewriteFlow) {
		isSpecialError = true
		if errors.As(err, &errSwitchFlow) {
			output, err = s.switchFlow(ctx, session, errSwitchFlow)
		}

		if errors.As(err, &errRewriteFlow) {
			output, err = s.rewriteFlow(ctx, session, errRewriteFlow)
		}
	}

	if isSpecialError {
		return
	}

	// 步骤5：处理流程结束
	isEOF := errors.Is(err, ErrEOF)
	if err != nil && !isEOF {
		return
	}

	sessionOutput := session.ToOutput()

	var cookies []*http.Cookie
	if isEOF {
		// 在事务中完成流程
		err = s.Database.WithTx(ctx, func(ctx context.Context) error {
			cookies, err = s.finishFlow(ctx, flow)
			return err
		})
		if err != nil {
			return
		}

		// 清理会话和流程
		err = s.Store.DeleteSession(ctx, session)
		if err != nil {
			return
		}

		err = s.Store.DeleteFlow(ctx, flow)
		if err != nil {
			return
		}
	}

	// 保留 EOF 信号
	if isEOF {
		err = ErrEOF
	}

	// 步骤6：构造输出
	flowReference := FindCurrentFlowReference(flow)
	output = &ServiceOutput{
		Session:       session,
		SessionOutput: sessionOutput,
		Flow:          flow,
		FlowReference: flowReference,
		FlowAction:    flowAction,
		Cookies:       cookies,
	}
	return
}

// FeedSyntheticInput 向流程提供预先生成的合成输入
// 与 FeedInput 不同，这里直接传入构造好的 Input 对象，而不是原始 JSON
//
// 使用场景：
// - 系统内部自动处理某些步骤
// - 流程切换后自动注入某些输入
// - 测试时注入特定输入
//
// 参数：
//   - stateToken: 状态令牌
//   - syntheticInput: 预先生成的输入对象
//
// 执行流程与 FeedInput 类似
func (s *Service) FeedSyntheticInput(ctx context.Context, stateToken string, syntheticInput Input) (output *ServiceOutput, err error) {
	// 获取流程
	flow, err := s.Store.GetFlowByStateToken(ctx, stateToken)
	if err != nil {
		return
	}

	// 获取会话并更新上下文
	ctx, session, err := s.getSessionAndUpdateContext(ctx, flow.FlowID)
	if err != nil {
		return
	}

	// 处理合成输入
	var flowAction *FlowAction
	flow, flowAction, err = s.feedSyntheticInput(ctx, session, stateToken, syntheticInput)

	// 处理流程结束
	isEOF := errors.Is(err, ErrEOF)
	if err != nil && !isEOF {
		return
	}

	sessionOutput := session.ToOutput()

	var cookies []*http.Cookie
	if isEOF {
		err = s.Database.WithTx(ctx, func(ctx context.Context) error {
			cookies, err = s.finishFlow(ctx, flow)
			return err
		})
		if err != nil {
			return
		}

		err = s.Store.DeleteSession(ctx, session)
		if err != nil {
			return
		}

		err = s.Store.DeleteFlow(ctx, flow)
		if err != nil {
			return
		}
	}

	// 保留 EOF 信号
	if isEOF {
		err = ErrEOF
	}

	// 构造输出
	flowReference := FindCurrentFlowReference(flow)
	output = &ServiceOutput{
		Session:       session,
		SessionOutput: sessionOutput,
		Flow:          flow,
		FlowReference: flowReference,
		FlowAction:    flowAction,
		Cookies:       cookies,
	}
	return
}

// switchFlow 切换到新流程
//
// 使用场景：
// 当用户在登录过程中发现还没有账号，想切换到注册流程时。
// 或者某些条件触发需要切换到不同的流程。
//
// 执行流程：
// 1. 根据流程引用实例化新流程
// 2. 创建新流程（复用现有会话）
// 3. 向新流程注入合成输入（携带切换前的状态）
// 4. 合并两个流程产生的 Cookie
func (s *Service) switchFlow(ctx context.Context, session *Session, errSwitchFlow *ErrorSwitchFlow) (output *ServiceOutput, err error) {
	// 步骤1：实例化新流程
	// 使用目标流程的引用（类型和名称）创建新流程对象
	publicFlow, err := InstantiateFlow(errSwitchFlow.FlowReference, jsonpointer.T{})
	if err != nil {
		return
	}

	// 步骤2：创建新流程
	// 复用现有的会话，保持用户状态连续性
	createOutput, err := s.createNewFlowWithSession(ctx, publicFlow, session)
	if err != nil {
		return
	}

	// 步骤3：注入合成输入
	// 将切换前收集的信息（如用户已输入的邮箱）注入新流程
	output, err = s.FeedSyntheticInput(ctx, createOutput.Flow.StateToken, errSwitchFlow.SyntheticInput)
	if err != nil {
		return
	}

	// 步骤4：合并 Cookie
	// 新流程和原流程可能都产生了 Cookie，需要合并
	var cookies []*http.Cookie
	for _, c := range createOutput.Cookies {
		cookies = append(cookies, c)
	}
	for _, c := range output.Cookies {
		cookies = append(cookies, c)
	}
	output.Cookies = cookies

	return
}

// rewriteFlow 重写（重建）当前流程
//
// 使用场景：
// 当需要保留会话但完全重置流程时使用。
// 例如：用户想重新开始认证流程，但保留会话状态。
//
// 与 switchFlow 的区别：
// - switchFlow: 切换到不同类型的流程（登录→注册）
// - rewriteFlow: 重建同类型流程，但可能从不同的点开始
//
// 执行流程：
// 1. 创建新 Flow 对象（复用 session.FlowID）
// 2. 复制指定的节点（可能是部分流程状态）
// 3. 保存新流程
// 4. 注入合成输入
func (s *Service) rewriteFlow(ctx context.Context, session *Session, errRewriteFlow *ErrorRewriteFlow) (output *ServiceOutput, err error) {
	// 步骤1：创建新 Flow
	// 使用相同的 FlowID 保持会话关联
	newFlow := NewFlow(session.FlowID, errRewriteFlow.Intent)

	// 步骤2：复制节点
	// 这些节点可能表示需要保留的流程状态
	newFlow.Nodes = errRewriteFlow.Nodes

	// 步骤3：保存流程
	err = s.Store.CreateFlow(ctx, newFlow)
	if err != nil {
		return
	}

	// 步骤4：注入合成输入并继续
	return s.FeedSyntheticInput(ctx, newFlow.StateToken, errRewriteFlow.SyntheticInput)
}

func (s *Service) feedInput(ctx context.Context, session *Session, stateToken string, rawMessage json.RawMessage) (flow *Flow, flowAction *FlowAction, err error) {
	flow, err = s.Store.GetFlowByStateToken(ctx, stateToken)
	if err != nil {
		return
	}

	var shouldAccept = true
	for shouldAccept {
		shouldAccept = false
		var acceptResult *AcceptResult = NewAcceptResult()
		flows := NewFlows(flow)
		err = s.Database.ReadOnly(ctx, func(ctx context.Context) error {
			// Apply the run-effects.
			err = ApplyRunEffects(ctx, s.Deps, flows)
			if err != nil {
				return err
			}

			err = Accept(ctx, s.Deps, flows, acceptResult, rawMessage)
			isEOF := errors.Is(err, ErrEOF)
			if err != nil && !isEOF {
				return err
			}
			flowAction, err = s.getFlowAction(ctx, session, flow)
			if err != nil {
				return err
			}
			if isEOF {
				return ErrEOF
			}
			return nil
		})
		acceptErr := s.processAcceptResult(ctx, session, flows, acceptResult)
		if acceptErr != nil {
			return nil, nil, acceptErr
		}

		if errors.Is(err, ErrPauseAndRetryAccept) {
			shouldAccept = true
			err = nil
		}
	}

	isEOF := errors.Is(err, ErrEOF)
	if err != nil && !isEOF {
		return
	}

	// err is nil or err is ErrEOF.
	// We persist the flow state.
	//
	// 【重要说明】为什么这里直接使用 flow，而不是 flows.Nearest？
	// 以及：ApplyRunEffects 中的 Replace 会不会改变 flows.Nearest？
	//
	// 1. 初始状态（第859行）：
	//    flows := NewFlows(flow)
	//    NewFlows 返回 Flows{Root: flow, Nearest: flow}
	//    此时：flows.Nearest 和 flow 指向同一个内存对象
	//
	// 2. ApplyRunEffects 中的 Replace 真相：
	//    ApplyRunEffects 内部调用 flows.Replace(w) 时：
	//    - Replace 是【值接收者】方法：func (w Flows) Replace(...) Flows
	//    - 它修改的是【方法接收者的副本】的 Nearest 字段
	//    - 然后返回这个修改后的副本
	//    - 原始的 flows 变量【完全没有被修改】！
	//
	//    代码流程：
	//        // flows.Nearest 仍然是 flow（未被修改）
	//        newFlows := flows.Replace(someOtherFlow)
	//        // newFlows.Nearest 指向 someOtherFlow
	//        // 但 flows.Nearest 仍然指向 flow！
	//
	//    为什么 Replace 设计为值接收者？
	//    - 目的是创建临时的 Flows 视图用于遍历，而不是改变原始 Flows
	//    - GetEffects 需要知道"当前遍历到哪个 Flow"，但不应该改变外部状态
	//
	// 3. Accept 函数中的修改：
	//    Accept → doAccept 的 defer 语句执行：
	//        flows.Nearest.StateToken = newStateToken()
	//    这里的 flows 就是第859行创建的变量，没有被 Replace 修改过！
	//    所以：flows.Nearest 仍然 = flow，修改 StateToken 就是修改 flow.StateToken
	//
	// 4. 总结指针关系：
	//    feedInput 函数中始终只有一个 flows 变量（第859行创建）
	//    它从未被重新赋值，所以 flows.Nearest 始终指向 flow
	//    Replace 返回的新 Flows 只是临时使用，不影响外部 flows 变量
	//
	// 5. 为什么直接使用 flow？
	//    flow 和 flows.Nearest 指向同一对象，Accept 通过后者修改了前者
	//    所以 flow 已经包含最新的 StateToken 和节点数据
	err = s.Store.CreateFlow(ctx, flow)
	if err != nil {
		return
	}

	if isEOF {
		err = ErrEOF
	}
	return
}

func (s *Service) feedSyntheticInput(ctx context.Context, session *Session, stateToken string, syntheticInput Input) (flow *Flow, flowAction *FlowAction, err error) {
	flow, err = s.Store.GetFlowByStateToken(ctx, stateToken)
	if err != nil {
		return
	}

	var shouldAccept = true
	for shouldAccept {
		shouldAccept = false
		var acceptResult *AcceptResult = NewAcceptResult()
		flows := NewFlows(flow)
		err = s.Database.ReadOnly(ctx, func(ctx context.Context) error {
			// Apply the run-effects.
			err = ApplyRunEffects(ctx, s.Deps, flows)
			if err != nil {
				return err
			}

			err = AcceptSyntheticInput(ctx, s.Deps, flows, acceptResult, syntheticInput)
			isEOF := errors.Is(err, ErrEOF)
			if err != nil && !isEOF {
				return err
			}
			flowAction, err = s.getFlowAction(ctx, session, flow)
			if err != nil {
				return err
			}
			if isEOF {
				return ErrEOF
			}
			return nil
		})
		acceptErr := s.processAcceptResult(ctx, session, flows, acceptResult)
		if acceptErr != nil {
			return nil, nil, acceptErr
		}

		if errors.Is(err, ErrPauseAndRetryAccept) {
			shouldAccept = true
			err = nil
		}
	}

	isEOF := errors.Is(err, ErrEOF)
	if err != nil && !isEOF {
		return
	}

	// err is nil or err is ErrEOF.
	// We persist the flow state.
	err = s.Store.CreateFlow(ctx, flow)
	if err != nil {
		return
	}

	if isEOF {
		err = ErrEOF
	}
	return
}

// finishFlow 完成流程
// 在流程成功结束时调用，应用所有效果并收集 Cookie
//
// 什么是"效果"（Effects）？
// 效果是流程执行过程中产生的副作用，分为两类：
// 1. RunEffects: 运行时效果，如记录日志、发送验证码
// 2. OnCommitEffects: 提交时效果，如创建用户、更新数据库
//
// 执行流程：
// 1. 应用所有效果（包括 RunEffects 和 OnCommitEffects）
// 2. 收集需要返回给客户端的 Cookie
func (s *Service) finishFlow(ctx context.Context, flow *Flow) (cookies []*http.Cookie, err error) {
	// 步骤1：应用所有效果
	// 这会真正执行流程中积累的所有副作用
	// 例如：创建新用户、发送欢迎邮件、记录登录日志等
	err = ApplyAllEffects(ctx, s.Deps, NewFlows(flow))
	if err != nil {
		return
	}

	// 步骤2：收集 Cookie
	// 流程可能产生了需要设置的 Cookie，如会话 Cookie
	cookies, err = CollectCookies(ctx, s.Deps, NewFlows(flow))
	if err != nil {
		return
	}

	return
}

// getFlowAction 获取当前流程的动作
//
// 什么是 FlowAction？
// 它告诉客户端"下一步该做什么"。例如：
// - "请输入密码"（需要用户输入）
// - "请选择验证方式"（需要用户选择）
// - "流程已完成"（可以跳转到成功页面）
//
// 执行逻辑：
// 1. 查找当前等待输入的节点（InputReactor）
// 2. 如果是 EOF（流程结束），构造完成动作
// 3. 否则根据节点类型构造相应的动作
func (s *Service) getFlowAction(ctx context.Context, session *Session, flow *Flow) (flowAction *FlowAction, err error) {
	// 查找当前等待输入的节点
	findInputReactorResult, err := FindInputReactor(ctx, s.Deps, NewFlows(flow))

	// 情况1：流程已结束（EOF）
	if errors.Is(err, ErrEOF) {
		// 获取重定向 URI（可能是登录前的页面或默认页面）
		redirectURI := session.RedirectURI

		// 尝试获取认证信息条目
		e, ok := GetAuthenticationInfoEntry(ctx, s.Deps, NewFlows(flow))
		if ok {
			// 将认证信息添加到重定向 URI 的查询参数中
			// 这样前端可以知道登录成功后的用户信息
			redirectURI = s.UIInfoResolver.SetAuthenticationInfoInQuery(redirectURI, e)
		}

		// 构造完成数据
		dataFinishRedirectURI := &DataFinishRedirectURI{
			FinishRedirectURI: redirectURI,
		}
		var data Data = dataFinishRedirectURI

		// 如果意图实现了 EndOfFlowDataOutputer 接口，允许它自定义输出数据
		if outputer, ok := flow.Intent.(EndOfFlowDataOutputer); ok {
			data, err = outputer.OutputEndOfFlowData(ctx, s.Deps, NewFlows(flow), dataFinishRedirectURI)
			if err != nil {
				return nil, err
			}
		}

		// 构造"完成"动作
		flowAction = &FlowAction{
			Type: FlowActionTypeFinished, // 动作类型：已完成
			Data: data,                   // 包含重定向 URI 等信息
		}
		return
	}

	// 其他错误，直接返回
	if err != nil {
		return nil, err
	}

	// 情况2：流程需要用户输入
	if findInputReactorResult.InputSchema != nil {
		// 获取当前位置的 JSON 指针
		p := findInputReactorResult.InputSchema.GetJSONPointer()
		// 获取流程根对象配置
		flowRootObject := findInputReactorResult.InputSchema.GetFlowRootObject()

		// 根据配置获取对应的流程动作
		if flowRootObject != nil {
			flowAction = GetFlowAction(flowRootObject, p)
		}
	}

	// 如果输入响应者能输出数据，获取附加数据
	var data Data
	if dataOutputer, ok := findInputReactorResult.InputReactor.(DataOutputer); ok {
		data, err = dataOutputer.OutputData(ctx, s.Deps, findInputReactorResult.Flows)
		if err != nil {
			return nil, err
		}
	}

	// 如果没有数据，使用空 map
	if data == nil {
		data = mapData{}
	}

	// 将数据附加到动作
	if flowAction != nil {
		flowAction.Data = data
	}

	return
}

// resolveStateTokenFromInput 从输入中解析状态令牌
//
// 使用场景：
// 这是特殊场景的处理函数，主要用于"账号恢复"流程。
// 当用户点击账号恢复邮件中的链接时，他们没有 stateToken，
// 而是有一个恢复码（AccountRecoveryCode）。
//
// 执行流程：
// 1. 尝试从输入中提取账号恢复码
// 2. 验证恢复码
// 3. 根据恢复码中的信息创建新流程
// 4. 返回新流程的 stateToken
func (s *Service) resolveStateTokenFromInput(ctx context.Context, inputRawMessage json.RawMessage) (string, error) {
	// 尝试提取账号恢复码
	if input, ok := MakeInputTakeAccountRecoveryCode(ctx, inputRawMessage); ok {
		// 验证恢复码
		state, err := s.Deps.ResetPassword.VerifyCode(ctx, input.AccountRecoveryCode)
		if err != nil {
			return "", err
		}

		// 根据恢复码中的信息实例化流程
		// 恢复码保存了原来的流程类型和名称
		flow, err := InstantiateFlow(FlowReference{
			Type: FlowType(state.AuthenticationFlowType),
			Name: state.AuthenticationFlowName,
		}, state.AuthenticationFlowJSONPointer)
		if err != nil {
			return "", err
		}

		// 在账号恢复流程中，会话选项不重要
		// 创建新流程获取 stateToken
		newFlowOutput, err := s.CreateNewFlow(ctx, flow, &SessionOptions{})
		if err != nil {
			return "", err
		}

		// 返回新流程的 stateToken
		return newFlowOutput.Flow.StateToken, nil
	}

	// 无法从输入中解析 stateToken
	return "", ErrFlowNotFound
}

// getSessionAndUpdateContext 获取会话并更新上下文
//
// 为什么需要更新上下文？
// 上下文（context）在 Go 中用于传递请求范围的数据和信号。
// 将会话信息注入上下文后，下游函数可以通过上下文获取会话信息，
// 而不需要显式传递会话参数。
//
// 执行流程：
// 1. 根据 flowID 获取会话（FlowID 是关联 Session 和 Flow 的纽带）
// 2. 将会话信息注入上下文
//
// 参数：
//   - ctx: 原始上下文
//   - flowID: 流程 ID（与 Session ID 相同）
//
// 返回：
//   - 更新后的上下文
//   - 会话对象
//   - 可能的错误
func (s *Service) getSessionAndUpdateContext(ctx context.Context, flowID string) (context.Context, *Session, error) {
	// 从存储获取会话
	session, err := s.Store.GetSession(ctx, flowID)
	if err != nil {
		return ctx, nil, err
	}

	// 将会话信息注入上下文
	// 这样后续代码可以通过 ctx 获取会话
	ctx = session.MakeContext(ctx, s.Deps)

	return ctx, session, nil
}
