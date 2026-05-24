package declarative

import (
	"context"
	"fmt"

	"github.com/iawaknahc/jsonschema/pkg/jsonpointer"

	"github.com/authgear/authgear-server/pkg/api/model"
	authflow "github.com/authgear/authgear-server/pkg/lib/authenticationflow"
	"github.com/authgear/authgear-server/pkg/lib/authn/authenticator"
	"github.com/authgear/authgear-server/pkg/lib/authn/otp"
)

func init() {
	authflow.RegisterIntent(&IntentAuthenticationOOB{})
}

// IntentAuthenticationOOB 是用于处理 OOB (Out-of-Band) OTP 认证的意图
// 它支持通过短信或电子邮件发送一次性密码来进行身份验证
// 字段说明:
//   - JSONPointer: 当前意图在 JSON 结构中的位置指针
//   - UserID: 正在进行认证的用户 ID
//   - Purpose: OTP 的用途(如验证、登录等)
//   - Form: OTP 的格式类型
//   - Info: 认证器信息，包含 OOB OTP 相关配置
//   - Authentication: 认证流程的类型标识
type IntentAuthenticationOOB struct {
	JSONPointer    jsonpointer.T                          `json:"json_pointer,omitempty"`
	UserID         string                                 `json:"user_id,omitempty"`
	Purpose        otp.Purpose                            `json:"purpose,omitempty"`
	Form           otp.Form                               `json:"form,omitempty"`
	Info           *authenticator.Info                    `json:"info,omitempty"`
	Authentication model.AuthenticationFlowAuthentication `json:"authentication,omitempty"`
}

var _ authflow.Intent = &IntentAuthenticationOOB{}
var _ authflow.DataOutputer = &IntentAuthenticationOOB{}
var _ authflow.Milestone = &IntentAuthenticationOOB{}
var _ MilestoneDoMarkClaimVerified = &IntentAuthenticationOOB{}

// Kind 返回意图的类型标识符，用于在认证流程中识别此意图
func (*IntentAuthenticationOOB) Kind() string {
	return "IntentAuthenticationOOB"
}

// Milestone 实现里程碑接口，标识此意图是一个流程节点
func (*IntentAuthenticationOOB) Milestone() {}

// MilestoneDoMarkClaimVerified 实现声明验证里程碑接口
func (*IntentAuthenticationOOB) MilestoneDoMarkClaimVerified() {}

// MilestoneDoMarkClaimVerifiedUpdateUserID 更新用户 ID，用于声明验证流程中
func (i *IntentAuthenticationOOB) MilestoneDoMarkClaimVerifiedUpdateUserID(newUserID string) {
	i.UserID = newUserID
}

// CanReactTo 判断当前意图是否可以响应输入，返回期望的输入结构
// 流程逻辑:
//  1. 如果 OOB OTP 尚未验证(verified 为 false):
//     - 如果只有一个可用的通道(短信或邮件)，则不需要用户输入，直接继续
//     - 如果有多个通道，返回输入结构让用户选择通道
//  2. 如果 OOB OTP 已验证但最后一次使用的通道未更新，继续等待
//  3. 所有步骤完成，返回 ErrEOF 表示流程结束
func (i *IntentAuthenticationOOB) CanReactTo(ctx context.Context, deps *authflow.Dependencies, flows authflow.Flows) (authflow.InputSchema, error) {
	_, _, verified := authflow.FindMilestoneInCurrentFlow[MilestoneOOBOTPVerified](flows)
	if !verified {
		// We have a special case here.
		// If there is only one channel, we do not take any input.
		// The rationale is that the only possible input is that channel.
		// So it is trivial that we can proceed without the input.
		// 特殊情况处理: 当只有一个可用通道时，不需要用户输入
		// 因为唯一的输入就是那个通道，所以可以直接继续流程
		channels := i.getChannels(deps)
		if len(channels) == 1 {
			return nil, nil
		}
		flowRootObject, err := findNearestFlowObjectInFlow(deps, flows, i)
		if err != nil {
			return nil, err
		}
		return &InputSchemaTakeOOBOTPChannel{
			JSONPointer:    i.JSONPointer,
			FlowRootObject: flowRootObject,
			Channels:       channels,
		}, nil
	}

	_, _, lastUsedChannelUpdated := authflow.FindMilestoneInCurrentFlow[MilestoneOOBOTPLastUsedChannelUpdated](flows)
	if !lastUsedChannelUpdated {
		return nil, nil
	}

	return nil, authflow.ErrEOF
}

// ReactTo 处理用户的输入并返回下一步的节点或结果
// 处理流程:
//  1. 如果 OOB OTP 尚未验证:
//     - 确定要使用的通道(单一通道直接选择，多通道需从输入获取)
//     - 创建 NodeAuthenticationOOB 节点处理 OTP 发送
//  2. 如果 OOB OTP 已验证但最后一次使用的通道未更新:
//     - 创建 NodeDoUpdateLastUsedChannel 节点更新最后使用通道
//  3. 如果所有条件都不满足，触发 panic(理论上不应到达)
func (i *IntentAuthenticationOOB) ReactTo(ctx context.Context, deps *authflow.Dependencies, flows authflow.Flows, input authflow.Input) (authflow.ReactToResult, error) {
	milestone, _, verified := authflow.FindMilestoneInCurrentFlow[MilestoneOOBOTPVerified](flows)
	if !verified {
		channels := i.getChannels(deps)
		var inputTakeOOBOTPChannel inputTakeOOBOTPChannel
		var channel model.AuthenticatorOOBChannel

		switch {
		case len(channels) == 1:
			// 只有一个通道时，直接使用该通道
			channel = channels[0]
		case authflow.AsInput(input, &inputTakeOOBOTPChannel):
			// 从用户输入中获取选择的通道
			channel = inputTakeOOBOTPChannel.GetChannel()
		default:
			return nil, authflow.ErrIncompatibleInput
		}

		// 创建 OOB 认证节点，负责发送 OTP 到指定通道
		node, err := NewNodeAuthenticationOOB(ctx, deps, &NodeAuthenticationOOB{
			JSONPointer:    i.JSONPointer,
			UserID:         i.UserID,
			Purpose:        i.Purpose,
			Form:           i.Form,
			Info:           i.Info,
			Channel:        channel,
			Authentication: i.Authentication,
		})
		if err != nil {
			return nil, err
		}
		return node, nil
	}

	_, _, lastUsedChannelUpdated := authflow.FindMilestoneInCurrentFlow[MilestoneOOBOTPLastUsedChannelUpdated](flows)
	if !lastUsedChannelUpdated {
		// 更新最后使用的通道信息
		return authflow.NewNodeSimple(&NodeDoUpdateLastUsedChannel{
			Channel: milestone.MilestoneOOBOTPVerifiedChannel(),
			Info:    i.Info,
		}), nil
	}

	panic(fmt.Errorf("unexpected: unreachable code reached"))
}

// OutputData 返回此意图的输出数据，用于向客户端展示可用通道和掩码后的目标地址
// 返回的数据包括:
//   - Channels: 可用的 OOB 通道列表(如短信、邮箱)
//   - MaskedClaimValue: 掩码处理后的手机号或邮箱地址，用于提示用户验证码发送到哪里
func (i *IntentAuthenticationOOB) OutputData(ctx context.Context, deps *authflow.Dependencies, flows authflow.Flows) (authflow.Data, error) {
	channels := i.getChannels(deps)
	claimName, claimValue := i.Info.OOBOTP.ToClaimPair()
	return NewOOBData(SelectOOBOTPChannelsData{
		Channels:         channels,
		MaskedClaimValue: getMaskedOTPTarget(claimName, claimValue),
	}), nil
}

// getChannels 根据认证器声明名称获取可用的 OOB 通道列表
// 例如: 如果声明是手机号，可能返回短信通道；如果是邮箱，可能返回邮件通道
func (i *IntentAuthenticationOOB) getChannels(deps *authflow.Dependencies) []model.AuthenticatorOOBChannel {
	claimName, _ := i.Info.OOBOTP.ToClaimPair()
	return getChannels(claimName, deps.Config.Authenticator.OOB)
}
