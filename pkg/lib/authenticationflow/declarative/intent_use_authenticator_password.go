// 声明包名，这个包存放与认证流程相关的声明式代码
package declarative

import (
	// context 用于传递上下文，包含超时、取消信号等信息
	"context"

	// jsonpointer 用于处理 JSON 指针，定位 JSON 文档中的特定节点
	"github.com/iawaknahc/jsonschema/pkg/jsonpointer"

	// model 包包含 API 相关的数据模型定义
	"github.com/authgear/authgear-server/pkg/api/model"
	// authflow 是认证流程的核心包
	authflow "github.com/authgear/authgear-server/pkg/lib/authenticationflow"
	// authn 是身份验证相关的包
	"github.com/authgear/authgear-server/pkg/lib/authn"
	// authenticator 处理认证器相关逻辑
	"github.com/authgear/authgear-server/pkg/lib/authn/authenticator"
	// facade 提供统一的接口层
	"github.com/authgear/authgear-server/pkg/lib/facade"
)

// init 函数在包被导入时自动执行，用于初始化
func init() {
	// 注册 IntentUseAuthenticatorPassword 这个意图到认证流程系统
	authflow.RegisterIntent(&IntentUseAuthenticatorPassword{})
}

// IntentUseAuthenticatorPassword 定义了使用密码认证器进行认证时的意图
// 它包含了处理密码认证所需的所有上下文信息
type IntentUseAuthenticatorPassword struct {
	// JSONPointer 用于在流程对象中定位此意图的位置
	JSONPointer jsonpointer.T `json:"json_pointer,omitempty"`
	// UserID 是进行认证的用户 ID
	UserID string `json:"user_id,omitempty"`
	// Authentication 是当前使用的认证方法
	Authentication model.AuthenticationFlowAuthentication `json:"authentication,omitempty"`
}

// 以下代码使用编译器检查，确保 IntentUseAuthenticatorPassword 实现了指定的接口
// 如果未实现，会在编译时报错

// 实现 authflow.Intent 接口，表示这是一个认证流程意图
var _ authflow.Intent = &IntentUseAuthenticatorPassword{}
// 实现 authflow.Milestone 接口，表示这是一个里程碑节点
var _ authflow.Milestone = &IntentUseAuthenticatorPassword{}
// 实现 MilestoneFlowSelectAuthenticationMethod 接口，支持选择认证方法
var _ MilestoneFlowSelectAuthenticationMethod = &IntentUseAuthenticatorPassword{}
// 实现 MilestoneDidSelectAuthenticationMethod 接口，标记已选择认证方法
var _ MilestoneDidSelectAuthenticationMethod = &IntentUseAuthenticatorPassword{}
// 实现 MilestoneFlowAuthenticate 接口，支持认证流程
var _ MilestoneFlowAuthenticate = &IntentUseAuthenticatorPassword{}
// 实现 authflow.InputReactor 接口，表示可以接收用户输入
var _ authflow.InputReactor = &IntentUseAuthenticatorPassword{}

// Kind 返回此意图的字符串标识
// 这个标识用于在流程中识别和区分不同类型的意图
func (*IntentUseAuthenticatorPassword) Kind() string {
	return "IntentUseAuthenticatorPassword"
}

// Milestone 方法满足 Milestone 接口，标记这是一个里程碑节点
func (*IntentUseAuthenticatorPassword) Milestone() {}

// MilestoneFlowSelectAuthenticationMethod 是流程中用于选择认证方法的接口实现
// 它返回自身作为已实现 MilestoneDidSelectAuthenticationMethod 的节点
func (n *IntentUseAuthenticatorPassword) MilestoneFlowSelectAuthenticationMethod(flows authflow.Flows) (MilestoneDidSelectAuthenticationMethod, authflow.Flows, bool) {
	return n, flows, true
}

// MilestoneDidSelectAuthenticationMethod 返回当前选择的认证方法
// 供其他节点查询已选择的认证方式
func (n *IntentUseAuthenticatorPassword) MilestoneDidSelectAuthenticationMethod() model.AuthenticationFlowAuthentication {
	return n.Authentication
}

// MilestoneFlowAuthenticate 在当前流程中查找已完成认证的里程碑节点
// 如果用户已经通过认证，就可以找到 MilestoneDidAuthenticate
func (n *IntentUseAuthenticatorPassword) MilestoneFlowAuthenticate(flows authflow.Flows) (MilestoneDidAuthenticate, authflow.Flows, bool) {
	return authflow.FindMilestoneInCurrentFlow[MilestoneDidAuthenticate](flows)
}

// CanReactTo 判断当前意图是否可以响应输入
// 如果可以响应，返回期望的输入模式，供 UI 生成相应的输入界面
func (n *IntentUseAuthenticatorPassword) CanReactTo(ctx context.Context, deps *authflow.Dependencies, flows authflow.Flows) (authflow.InputSchema, error) {
	// 检查当前流程中是否已经有认证完成的里程碑
	// 如果已有，说明不需要再响应，返回 ErrEOF 表示流程结束
	_, _, authenticated := authflow.FindMilestoneInCurrentFlow[MilestoneDidAuthenticate](flows)
	if authenticated {
		return nil, authflow.ErrEOF
	}

	// 查找最近的流程对象，用于构建完整的流程上下文
	flowRootObject, err := findNearestFlowObjectInFlow(deps, flows, n)
	if err != nil {
		return nil, err
	}

	// 检查是否需要人机验证，防止暴力破解攻击
	isBotProtectionRequired, err := IsBotProtectionRequired(ctx, deps, flows, n.JSONPointer, n)
	if err != nil {
		return nil, err
	}

	// 返回期望的输入模式：密码输入界面，同时包含人机验证配置
	return &InputSchemaTakePassword{
		FlowRootObject:          flowRootObject,          // 流程根对象
		JSONPointer:             n.JSONPointer,             // 当前位置
		IsBotProtectionRequired: isBotProtectionRequired, // 是否需要验证
		BotProtectionCfg:        deps.Config.BotProtection, // 验证配置
	}, nil
}

// ReactTo 处理用户输入，执行密码验证
// 这是核心的认证逻辑，验证用户输入的密码是否正确
func (i *IntentUseAuthenticatorPassword) ReactTo(ctx context.Context, deps *authflow.Dependencies, flows authflow.Flows, input authflow.Input) (authflow.ReactToResult, error) {
	// 声明变量来存储输入，类型为 inputTakePassword
	var inputTakePassword inputTakePassword
	// 尝试将输入转换为期望的类型，如果类型不匹配则不处理
	if authflow.AsInput(input, &inputTakePassword) {
		// 变量用于存储人机验证的特殊错误（如果有）
		var bpSpecialErr error
		// 处理人机验证逻辑，防止机器人攻击
		bpSpecialErr, err := HandleBotProtection(ctx, deps, flows, i.JSONPointer, input, i)
		if err != nil {
			return nil, err
		}

		// 查询该用户的所有密码认证器
		// KeepKind 限制查询特定类型的认证器，KeepType 限制为密码类型
		as, err := deps.Authenticators.List(ctx,
			i.UserID,                                          // 用户 ID
			authenticator.KeepKind(i.Authentication.AuthenticatorKind()), // 认证器种类
			authenticator.KeepType(model.AuthenticatorTypePassword),      // 密码类型
		)
		if err != nil {
			return nil, err
		}

		// 从用户输入中获取明文密码
		password := inputTakePassword.GetPassword()
		// 创建认证器规格，包含用户输入的密码
		spec := &authenticator.Spec{
			Password: &authenticator.PasswordSpec{
				PlainPassword: password, // 明文密码
			},
		}

		// 使用规格验证用户输入的密码是否正确
		// VerifyOneWithSpec 会遍历所有认证器进行验证
		info, verifyResult, err := deps.Authenticators.VerifyOneWithSpec(ctx,
			i.UserID,                       // 用户 ID
			model.AuthenticatorTypePassword, // 认证器类型：密码
			as,                             // 用户的认证器列表
			spec,                           // 包含用户输入的规格
			&facade.VerifyOptions{
				AuthenticationDetails: facade.NewAuthenticationDetails(
					i.UserID, // 用户 ID
					authn.AuthenticationStageFromAuthenticationMethod(i.Authentication), // 认证阶段
					authn.AuthenticationTypePassword, // 认证类型：密码
				),
			},
		)
		if err != nil {
			return nil, err
		}

		// 判断是否需要强制修改密码
		var reason PasswordChangeReason // 修改密码的原因
		if verifyResult.Password.ExpiryForceChange {
			// 密码已过期，必须修改
			reason = PasswordChangeReasonExpiry
		} else {
			// 根据策略需要修改（如首次登录、密码不符合新策略等）
			reason = PasswordChangeReasonPolicy
		}

		// 返回一个节点，表示使用密码认证器完成认证
		// 同时包含是否需要修改密码的信息
		return authflow.NewNodeSimple(&NodeDoUseAuthenticatorPassword{
			Authenticator:          info,                             // 认证成功的认证器信息
			PasswordChangeRequired: verifyResult.Password.RequireUpdate(), // 是否需要修改密码
			PasswordChangeReason:   reason,                           // 修改原因
			JSONPointer:            i.JSONPointer,                    // 当前位置
		}), bpSpecialErr // 同时返回可能的人机验证错误
	}

	// 输入类型不匹配，返回不兼容错误
	return nil, authflow.ErrIncompatibleInput
}
