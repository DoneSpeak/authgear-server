// Package declarative 实现了声明式的认证流程
// 本文件处理 OOB OTP (Out-of-Band One-Time Password) 认证器的使用
// OOB OTP 是通过外部渠道（如邮箱、短信）发送的一次性验证码
package declarative

import (
	"context"
	"errors"
	"fmt"

	"github.com/iawaknahc/jsonschema/pkg/jsonpointer"

	"github.com/authgear/authgear-server/pkg/api/model"
	authflow "github.com/authgear/authgear-server/pkg/lib/authenticationflow"
	"github.com/authgear/authgear-server/pkg/lib/authn/authenticator"
	"github.com/authgear/authgear-server/pkg/lib/authn/identity"
	"github.com/authgear/authgear-server/pkg/lib/authn/otp"
)

func init() {
	// 注册 IntentUseAuthenticatorOOBOTP 到认证流程系统中
	// 这样系统在遇到此类型的 Intent 时能够正确处理
	authflow.RegisterIntent(&IntentUseAuthenticatorOOBOTP{})
}

// IntentUseAuthenticatorOOBOTP 是使用 OOB OTP 认证器的 Intent
// 这个 Intent 负责处理用户通过邮箱或手机验证码进行认证的逻辑
// 包含以下阶段：
// 1. 选择认证方式（邮箱或手机）
// 2. 验证声明（发送并验证 OTP）
// 3. 完成认证
type IntentUseAuthenticatorOOBOTP struct {
	// JSONPointer 是当前流程节点在 JSON 结构中的路径位置
	// 用于在流程数据中精确定位此节点
	JSONPointer jsonpointer.T `json:"json_pointer,omitempty"`
	// UserID 是当前用户 ID，标识正在进行认证的用户
	UserID string `json:"user_id,omitempty"`
	// Authentication 是认证方式类型（如 email 或 phone）
	Authentication model.AuthenticationFlowAuthentication `json:"authentication,omitempty"`
	// Options 是可用的认证选项列表
	// 用户可从中选择一个认证方式
	Options []AuthenticateOption `json:"options,omitempty"`
}

// 编译时接口检查：确保 IntentUseAuthenticatorOOBOTP 实现了所需的接口
var _ authflow.Intent = &IntentUseAuthenticatorOOBOTP{}                         // 基础 Intent 接口
var _ authflow.Milestone = &IntentUseAuthenticatorOOBOTP{}                      // 里程碑标记接口
var _ MilestoneFlowSelectAuthenticationMethod = &IntentUseAuthenticatorOOBOTP{} // 选择认证方法里程碑
var _ MilestoneDidSelectAuthenticationMethod = &IntentUseAuthenticatorOOBOTP{}  // 已选择认证方法里程碑
var _ MilestoneFlowAuthenticate = &IntentUseAuthenticatorOOBOTP{}               // 认证流程里程碑

// Kind 返回此 Intent 的类型标识符
// 用于在认证流程中识别和路由到此 Intent
func (*IntentUseAuthenticatorOOBOTP) Kind() string {
	return "IntentUseAuthenticatorOOBOTP"
}

// Milestone 标记此类型实现了 Milestone 接口的空方法
func (*IntentUseAuthenticatorOOBOTP) Milestone() {}

// MilestoneFlowSelectAuthenticationMethod 实现选择认证方法里程碑
// 返回此 Intent 自身作为已选择认证方法的里程碑实现
// 表示此 Intent 直接处理认证方法的选择
func (i *IntentUseAuthenticatorOOBOTP) MilestoneFlowSelectAuthenticationMethod(flows authflow.Flows) (MilestoneDidSelectAuthenticationMethod, authflow.Flows, bool) {
	return i, flows, true
}

// MilestoneDidSelectAuthenticationMethod 返回已选择的认证方法
// 用于获取当前选中的认证方式（邮箱或手机）
func (i *IntentUseAuthenticatorOOBOTP) MilestoneDidSelectAuthenticationMethod() model.AuthenticationFlowAuthentication {
	return i.Authentication
}

// MilestoneFlowAuthenticate 查找并返回认证完成的里程碑
// 在流程中搜索已认证的标记，用于判断认证是否已完成
func (*IntentUseAuthenticatorOOBOTP) MilestoneFlowAuthenticate(flows authflow.Flows) (MilestoneDidAuthenticate, authflow.Flows, bool) {
	return authflow.FindMilestoneInCurrentFlow[MilestoneDidAuthenticate](flows)
}

// CanReactTo 判断当前 Intent 可以响应的输入类型
// 根据流程当前的状态决定下一步需要用户输入什么：
// 1. 如果认证器未选择：返回输入模式，让用户选择认证方式（邮箱/手机）
// 2. 如果声明未验证：需要验证 OTP 声明
// 3. 如果未认证：需要完成认证里程碑
// 4. 其他情况：流程结束
func (n *IntentUseAuthenticatorOOBOTP) CanReactTo(ctx context.Context, deps *authflow.Dependencies, flows authflow.Flows) (authflow.InputSchema, error) {
	// 检查当前流程状态：认证器是否已选择、声明是否已验证、是否已认证
	_, _, authenticatorSelected := authflow.FindMilestoneInCurrentFlow[MilestoneDidSelectAuthenticator](flows)
	_, _, claimVerified := authflow.FindMilestoneInCurrentFlow[MilestoneDoMarkClaimVerified](flows)
	_, _, authenticated := authflow.FindMilestoneInCurrentFlow[MilestoneDidAuthenticate](flows)

	// 获取流程根对象，用于构建输入模式
	flowRootObject, err := findNearestFlowObjectInFlow(deps, flows, n)
	if err != nil {
		return nil, err
	}

	switch {
	case !authenticatorSelected:
		// 阶段1：认证器未选择，需要用户选择认证方式
		// 检查是否需要绕过机器人保护验证（根据上下文已有结果）
		shouldBypassBotProtection := ShouldExistingResultBypassBotProtectionRequirement(ctx)
		return &InputSchemaUseAuthenticatorOOBOTP{
			FlowRootObject:            flowRootObject,
			JSONPointer:               n.JSONPointer,
			Options:                   n.Options,
			ShouldBypassBotProtection: shouldBypassBotProtection,
			BotProtectionCfg:          deps.Config.BotProtection,
		}, nil
	case !claimVerified:
		// 阶段2：声明未验证，需要验证 OTP 声明（发送并验证验证码）
		return nil, nil
	case !authenticated:
		// 阶段3：未认证，需要完成认证里程碑
		return nil, nil
	default:
		// 所有阶段已完成，返回 EOF 表示流程结束
		return nil, authflow.ErrEOF
	}
}

// ReactTo 处理用户输入并返回下一步操作
// 根据当前流程阶段处理不同的输入：
// 1. 认证器未选择时：处理用户选择的认证选项
// 2. 声明未验证时：启动 OTP 验证子流程
// 3. 未认证时：使用认证器完成认证
func (n *IntentUseAuthenticatorOOBOTP) ReactTo(ctx context.Context, deps *authflow.Dependencies, flows authflow.Flows, input authflow.Input) (authflow.ReactToResult, error) {
	// 获取当前流程状态
	m, _, authenticatorSelected := authflow.FindMilestoneInCurrentFlow[MilestoneDidSelectAuthenticator](flows)
	_, _, claimVerified := authflow.FindMilestoneInCurrentFlow[MilestoneDoMarkClaimVerified](flows)
	_, _, authenticated := authflow.FindMilestoneInCurrentFlow[MilestoneDidAuthenticate](flows)

	switch {
	case !authenticatorSelected:
		// 阶段1：处理认证器选择输入
		var inputTakeAuthenticationOptionIndex inputTakeAuthenticationOptionIndex
		if authflow.AsInput(input, &inputTakeAuthenticationOptionIndex) {
			// 首先处理机器人保护验证
			var bpSpecialErr error
			bpSpecialErr, err := HandleBotProtection(ctx, deps, flows, n.JSONPointer, input, n)
			if err != nil {
				return nil, err
			}
			// 获取用户选择的选项索引
			index := inputTakeAuthenticationOptionIndex.GetIndex()
			// 根据索引选择或创建认证器
			info, isNew, err := n.pickAuthenticator(ctx, deps, n.Options, index)
			if err != nil {
				return nil, errors.Join(bpSpecialErr, err)
			}

			// 如果是新创建的认证器（just-in-time），需要创建节点
			if isNew {
				return authflow.NewNodeSimple(&NodeDoJustInTimeCreateAuthenticator{
					Authenticator: info,
				}), bpSpecialErr
			}

			// 返回已选择认证器的节点
			return authflow.NewNodeSimple(&NodeDidSelectAuthenticator{
				Authenticator: info,
			}), bpSpecialErr
		}
	case !claimVerified:
		// 阶段2：声明未验证，启动 OTP 验证子流程
		info := m.MilestoneDidSelectAuthenticator()
		claimName, _ := info.OOBOTP.ToClaimPair()
		purpose := otp.PurposeOOBOTP
		otpForm := getOTPForm(purpose, claimName, deps.Config.Authenticator.OOB.Email)
		// 创建 OTP 验证子流程 Intent
		return authflow.NewSubFlow(&IntentAuthenticationOOB{
			JSONPointer:    n.JSONPointer,
			UserID:         n.UserID,
			Purpose:        purpose,
			Authentication: n.Authentication,
			Info:           info,
			Form:           otpForm,
		}), nil
	case !authenticated:
		// 阶段3：使用认证器完成认证
		info := m.MilestoneDidSelectAuthenticator()
		return authflow.NewNodeSimple(&NodeDoUseAuthenticatorSimple{
			Authenticator: info,
		}), nil
	}

	// 输入与当前阶段不兼容
	return nil, authflow.ErrIncompatibleInput
}

// pickAuthenticator 根据用户选择的索引获取或创建认证器
// 支持两种情况：
// 1. 选择已有认证器（通过 AuthenticatorID）
// 2. 基于身份信息即时创建新认证器（通过 IdentityID）
// 返回认证器信息、是否为新建认证器标志、以及可能的错误
// nolint:gocognit
func (n *IntentUseAuthenticatorOOBOTP) pickAuthenticator(ctx context.Context, deps *authflow.Dependencies, options []AuthenticateOption, index int) (info *authenticator.Info, isNew bool, err error) {
	// 遍历选项列表，找到与用户选择索引匹配的选项
	for idx, c := range options {
		if idx == index {
			switch {
			case c.AuthenticatorID != "":
				// 情况1：选项包含已有认证器ID，直接获取该认证器
				info, err = deps.Authenticators.Get(ctx, c.AuthenticatorID)
				if err != nil {
					return
				}

				return
			case c.IdentityID != "":
				// 情况2：选项包含身份信息ID，需要即时创建认证器
				var identityInfo *identity.Info
				identityInfo, err = deps.Identities.Get(ctx, c.IdentityID)
				if err != nil {
					return
				}

				// 基于身份信息创建认证器
				info, err = n.createAuthenticator(ctx, deps, identityInfo)
				if err != nil {
					return
				}

				// 检查即时创建的认证器是否与已有认证器重复
				// 这是为了避免创建重复的认证器
				var allAuthenticators []*authenticator.Info
				allAuthenticators, err = deps.Authenticators.List(ctx, n.UserID)
				if err != nil {
					return
				}

				// 遍历所有已有认证器，检查是否已存在相同的认证器
				for _, authenticator := range allAuthenticators {
					if authenticator.Equal(info) {
						// 发现已有相同认证器，使用现有认证器
						info = authenticator
						isNew = false
						return
					}
				}

				// 未找到重复认证器，需要即时创建新认证器
				isNew = true
				return
			default:
				// 选项格式无效，既无 AuthenticatorID 也无 IdentityID
				panic(fmt.Errorf("expected option to have either IdentityID or AuthenticatorID"))
			}
		}
	}

	// 未找到匹配的选项，返回输入不兼容错误
	err = authflow.ErrIncompatibleInput
	return
}

// createAuthenticator 基于身份信息即时创建 OOB OTP 认证器
// 此方法用于当用户选择了一个身份信息（如邮箱或手机号）但还没有对应的认证器时
// 会自动创建一个新的 OOB OTP 认证器供本次登录使用
// 参数：
//   - ctx: 上下文
//   - deps: 流程依赖项
//   - info: 身份信息（必须是 Login ID 类型）
//
// 返回：创建的认证器信息或错误
func (n *IntentUseAuthenticatorOOBOTP) createAuthenticator(ctx context.Context, deps *authflow.Dependencies, info *identity.Info) (*authenticator.Info, error) {
	// 验证身份信息类型：只能是 Login ID 类型（邮箱或手机）
	// OOB OTP 认证器只能从 Login ID 身份信息创建
	if info.Type != model.IdentityTypeLoginID || info.LoginID == nil {
		panic(fmt.Errorf("expected only Login ID identity can create OOB OTP authenticator just-in-time"))
	}

	// 获取登录ID作为 OTP 发送目标（邮箱地址或手机号码）
	target := info.LoginID.LoginID
	// 调用工具函数创建认证器
	authenticatorInfo, err := createAuthenticator(ctx, deps, n.UserID, n.Authentication, target)
	if err != nil {
		return nil, err
	}

	return authenticatorInfo, nil
}
