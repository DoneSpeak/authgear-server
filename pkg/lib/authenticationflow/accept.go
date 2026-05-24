// authenticationflow 包负责处理认证流程的核心逻辑
// 包括流程的执行、节点处理和状态管理
package authenticationflow

import (
	"context"       // Go 标准库：上下文，用于传递请求范围和取消信号
	"encoding/json" // Go 标准库：JSON 处理
	"errors"        // Go 标准库：错误处理
	"fmt"           // Go 标准库：格式化输入输出

	// 项目内部依赖包
	"github.com/authgear/authgear-server/pkg/api/apierrors"
	"github.com/authgear/authgear-server/pkg/api/event/nonblocking"
	"github.com/authgear/authgear-server/pkg/api/model"
	"github.com/authgear/authgear-server/pkg/lib/authn/user"
	"github.com/authgear/authgear-server/pkg/lib/botprotection"
	"github.com/authgear/authgear-server/pkg/lib/hook"
	"github.com/authgear/authgear-server/pkg/lib/lockout"
	"github.com/authgear/authgear-server/pkg/util/accesscontrol"
	"github.com/authgear/authgear-server/pkg/util/errorutil"
	"github.com/authgear/authgear-server/pkg/util/validation"
)

// AcceptResult 表示流程接受（执行）的结果
// 包含机器人保护验证结果和延迟执行的一次性函数
type AcceptResult struct {
	// BotProtectionVerificationResult 存储机器人保护验证的结果（如验证码校验结果）
	BotProtectionVerificationResult *BotProtectionVerificationResult `json:"bot_protection,omitempty"`
	// DelayedOneTimeFunctions 是需要延迟执行的一次性函数列表
	// json:"-" 表示这个字段不会序列化为 JSON
	DelayedOneTimeFunctions []DelayedOneTimeFunction `json:"-"`
}

// NewAcceptResult 创建一个新的 AcceptResult 实例
// 初始化时 DelayedOneTimeFunctions 为空切片（不是 nil）
func NewAcceptResult() *AcceptResult {
	return &AcceptResult{
		DelayedOneTimeFunctions: []DelayedOneTimeFunction{},
	}
}

// 定义常量：最大循环次数
// 防止流程无限循环，超过这个次数会 panic
const (
	MAX_LOOP = 100
)

// Accept 是执行认证流程的主要入口函数
// 它会将流程执行到最深处的节点，使用传入的原始 JSON 消息作为输入
//
// 参数说明：
//   - ctx: 上下文，用于控制请求生命周期和取消
//   - deps: 依赖注入容器，包含数据库、配置等依赖
//   - flows: 当前流程的集合（根流程和最近活跃的子流程）
//   - result: 执行结果，会被填充执行过程中的数据
//   - rawMessage: 用户输入的原始 JSON 数据（如用户名密码）
//
// 可能返回的错误：
//   - ErrEOF: 流程已到达终点（正常结束）
//   - ErrNoChange: 没有发生任何状态变化
//   - 其他由意图或节点产生的错误
func Accept(ctx context.Context, deps *Dependencies, flows Flows, result *AcceptResult, rawMessage json.RawMessage) error {
	return accept(ctx, deps, flows, result, func(inputSchema InputSchema) (Input, error) {
		// 如果传入的原始消息和输入模式都不为空，就根据模式解析输入
		if rawMessage != nil && inputSchema != nil {
			input, err := inputSchema.MakeInput(ctx, rawMessage)
			if err != nil {
				return nil, err
			}
			return input, nil
		}
		// 如果没有输入数据，返回 nil
		return nil, nil
	})
}

// AcceptSyntheticInput 使用预先生成的合成输入来执行流程
// 与 Accept 不同，这里直接传入已经构造好的 Input 对象，而不是原始 JSON
// 常用于系统自动处理的场景，比如自动填充某些认证步骤
func AcceptSyntheticInput(ctx context.Context, deps *Dependencies, flows Flows, result *AcceptResult, syntheticInput Input) error {
	return accept(ctx, deps, flows, result, func(inputSchema InputSchema) (Input, error) {
		// 直接返回预先生成的合成输入
		return syntheticInput, nil
	})
}

// accept 是内部的接受函数，封装了 doAccept 和错误处理逻辑
// inputFn 是一个函数，用于根据输入模式生成输入对象
func accept(ctx context.Context, deps *Dependencies, flows Flows, result *AcceptResult, inputFn func(inputSchema InputSchema) (Input, error)) error {
	// 执行核心的流程接受逻辑
	err := doAccept(ctx, deps, flows, result, inputFn)
	if err != nil {
		// 如果发生错误，检查是否需要记录认证被阻止的事件
		err = logAuthenticationBlockedErrorIfNeeded(ctx, deps, flows, err)
		if err != nil {
			return err
		}
	}
	return err
}

// logAuthenticationBlockedErrorIfNeeded 检查错误是否需要记录认证被阻止的事件
// 当用户账户被锁定、禁用或钩子拒绝时，会发送一个审计事件
func logAuthenticationBlockedErrorIfNeeded(ctx context.Context, deps *Dependencies, flows Flows, err error) error {
	// 如果不是 API 错误，直接返回原错误
	if !apierrors.IsAPIError(err) {
		return err
	}

	// 将错误转换为 API 错误类型
	apiErr := apierrors.AsAPIError(err)

	// 只有特定的错误才需要记录：账户状态错误、账户锁定、钩子拒绝
	if !user.IsAccountStatusError(apiErr) &&
		!apierrors.IsKind(apiErr, lockout.AccountLockout) &&
		!apierrors.IsKind(apiErr, hook.HookDisallowed) {
		return err
	}

	// 从流程中获取用户 ID
	userID, getUserIDErr := GetUserID(flows)
	if getUserIDErr != nil {
		// 如果是因为没有用户 ID 或多个用户 ID，将 userID 设为空字符串
		if errors.Is(getUserIDErr, ErrNoUserID) || errors.Is(getUserIDErr, ErrDifferentUserID) {
			userID = ""
		} else {
			// 其他错误，将两个错误合并返回
			return errors.Join(getUserIDErr, err)
		}
	}

	// 如果有用户 ID，从数据库获取用户对象
	var user *model.User
	if userID != "" {
		u, getUserErr := deps.Users.Get(ctx, userID, accesscontrol.RoleGreatest)
		if getUserErr != nil {
			return errors.Join(getUserErr, err)
		}
		user = u
	}

	// 发送认证被阻止的事件（用于审计日志）
	dispatchErr := deps.Events.DispatchEventImmediately(ctx, &nonblocking.AuthenticationBlockedEventPayload{
		User:  user,
		Error: apiErr,
	})
	if dispatchErr != nil {
		// 事件发送失败只记录日志，不影响主流程
		ServiceLogger.GetLogger(ctx).WithError(dispatchErr).Error(ctx, "failed to dispatch event")
	}
	return err
}

// doAccept 是核心的流程执行函数，使用循环不断处理节点直到流程结束
// nolint: gocognit - 忽略圈复杂度过高的警告，因为这个函数确实需要处理很多情况
func doAccept(ctx context.Context, deps *Dependencies, flows Flows, result *AcceptResult, inputFn func(inputSchema InputSchema) (Input, error)) (err error) {
	// changed 标记流程是否发生了变化（如添加了新节点）
	var changed bool

	// defer 语句：在函数返回前执行，用于设置状态令牌和检查变化
	defer func() {
		// 如果流程有变化，生成新的状态令牌（用于防止 CSRF 和状态篡改）
		if changed {
			flows.Nearest.StateToken = newStateToken()
		}
		// 如果流程没有变化且没有错误，返回 ErrNoChange 错误
		// 这样调用者知道这次请求没有实际效果
		if !changed && err == nil {
			err = ErrNoChange
		}
	}()

	// 循环计数器，防止无限循环
	loopCount := 0

	// 用于记录下一个节点的类型（仅用于错误信息）
	var nextNodeType string

	// 无限循环：不断执行流程直到遇到返回条件或错误
	for {
		loopCount += 1
		// 安全检查：如果循环次数超过限制，抛出 panic
		if loopCount > MAX_LOOP {
			panic(fmt.Errorf("number of loops reached limit. next node is %s", nextNodeType))
		}

		// 1. 找到可以接收输入的节点（InputReactor）
		var findInputReactorResult *FindInputReactorResult
		findInputReactorResult, err = FindInputReactor(ctx, deps, flows)
		if err != nil {
			return
		}

		// 找到 InputReactor，可以开始向它提供输入了

		// 2. 使用 inputFn 函数根据输入模式生成输入对象
		// input 默认为 nil
		var input Input
		input, err = inputFn(findInputReactorResult.InputSchema)

		// 特殊处理：如果循环已经执行过一次，将验证错误视为 ErrIncompatibleInput
		// 这意味着第一次输入验证失败后，流程会停止而不是报错
		var valiationError *validation.AggregatedError
		if errors.As(err, &valiationError) {
			if changed {
				err = nil
			}
			return
		} else if err != nil {
			return
		}

		// 3. 调用节点的 ReactTo 方法，让节点处理输入并决定下一步
		var reactToResult ReactToResult
		reactToResult, err = findInputReactorResult.InputReactor.ReactTo(ctx, deps, findInputReactorResult.Flows, input)

		// 4. 处理各种特殊错误情况

		// 4.1 处理 ErrIncompatibleInput（输入不兼容）
		// 这种情况通常意味着当前节点不需要这个输入，应该停止处理
		if errors.Is(err, ErrIncompatibleInput) {
			err = nil
			// 由于这是无限循环，changed 可能已经被设为 true
			return
		}

		// 4.2 处理 ErrSameNode（相同节点）
		// 节点处理输入后认为应该保持当前状态，但流程已发生变化
		if errors.Is(err, ErrSameNode) {
			err = nil
			// 仍认为流程有变化（因为已经执行了 ReactTo）
			changed = true
			// 必须在这里停止，因为这个输入处理器会无限处理这个输入
			return
		}

		// 4.3 处理 ErrReplaceNode（替换节点）
		// 需要原地替换最后一个节点
		if errors.Is(err, ErrReplaceNode) {
			err = nil
			changed = true

			var nodeToReplace *Node
			// 根据返回结果的不同类型，提取要替换的节点
			switch reactToResult := reactToResult.(type) {
			case *Node:
				nodeToReplace = reactToResult
			case *NodeWithDelayedOneTimeFunction:
				nodeToReplace = reactToResult.Node
				// 收集延迟执行的一次性函数
				result.DelayedOneTimeFunctions = append(result.DelayedOneTimeFunctions, reactToResult.DelayedOneTimeFunction)
			default:
				panic(fmt.Errorf("failed to update node: uxepected type of ReactToResult %t", reactToResult))
			}

			// 前置条件：ErrReplaceNode 要求至少有一个节点
			if len(findInputReactorResult.Flows.Nearest.Nodes) == 0 {
				panic(fmt.Errorf("input reactor %T returned ErrUpdateNode, but there are no nodes", findInputReactorResult.InputReactor))
			}

			// 原地更新最后一个节点
			findInputReactorResult.Flows.Nearest.Nodes[len(findInputReactorResult.Flows.Nearest.Nodes)-1] = *nodeToReplace

			// 必须在这里停止
			return
		}

		// 4.4 处理机器人保护验证错误
		var errBotProtectionVerification *ErrorBotProtectionVerification
		if errors.As(err, &errBotProtectionVerification) {
			// 将错误分为匹配和不匹配两部分
			_, notMatched := errorutil.Partition(err, func(err error) bool {
				var _errBPV *ErrorBotProtectionVerification
				return errors.As(err, &_errBPV) && _errBPV.Status == ErrorBotProtectionVerificationStatusSuccess
			})
			err = notMatched

			// 根据验证状态处理
			switch errBotProtectionVerification.Status {
			case ErrorBotProtectionVerificationStatusSuccess:
				// 验证成功，记录结果
				result.BotProtectionVerificationResult = &BotProtectionVerificationResult{
					Outcome: BotProtectionVerificationOutcomeVerified,
				}
			case ErrorBotProtectionVerificationStatusFailed:
				// 验证失败，记录结果并返回错误
				changed = true
				result.BotProtectionVerificationResult = &BotProtectionVerificationResult{
					Outcome: BotProtectionVerificationOutcomeFailed,
				}
				err = botprotection.ErrVerificationFailed
				return
			case ErrorBotProtectionVerificationStatusServiceUnavailable:
				// 验证服务不可用，记录结果并返回错误
				changed = true
				result.BotProtectionVerificationResult = &BotProtectionVerificationResult{
					Outcome: BotProtectionVerificationOutcomeFailed,
				}
				err = botprotection.ErrVerificationServiceUnavailable
				return
			default:
				// 未知状态，抛出 panic（这是编程错误）
				panic("unrecognized bot protection special error status in accept loop")
			}
		}

		// 4.5 处理其他错误
		if err != nil {
			// 包装错误，添加上下文信息
			err = newAuthenticationFlowError(flows, err)
			return
		}

		// 5. 没有错误，需要追加新节点到流程
		var nextNode Node
		// 根据返回结果的类型，提取下一个节点
		switch reactToResult := reactToResult.(type) {
		case *Node:
			nextNode = *reactToResult
		case *NodeWithDelayedOneTimeFunction:
			nextNode = *reactToResult.Node
			result.DelayedOneTimeFunctions = append(result.DelayedOneTimeFunctions, reactToResult.DelayedOneTimeFunction)
		default:
			panic(fmt.Errorf("failed to append node: uxepected type of ReactToResult %t", reactToResult))
		}

		// 记录下一个节点的类型（用于调试和错误信息）
		switch nextNode.Type {
		case NodeTypeSimple:
			nextNodeType = fmt.Sprintf("%T", nextNode.Simple)
		case NodeTypeSubFlow:
			nextNodeType = fmt.Sprintf("%T", nextNode.SubFlow.Intent)
		default:
			nextNodeType = "unknown"
		}
		// 取消注释下面这行来调试流程
		// fmt.Println("The next node is", nextNodeType)

		// 6. 将新节点追加到最近的流程
		err = appendNode(ctx, deps, findInputReactorResult.Flows, nextNode)
		if err != nil {
			return
		}
		// 标记流程已发生变化，会继续下一次循环
		changed = true
	}
}

// appendNode 将新节点追加到流程中，并立即执行节点的副作用（effects）
//
// 什么是副作用？例如：
//   - 发送验证码邮件
//   - 记录登录日志
//   - 更新用户的最后登录时间
func appendNode(ctx context.Context, deps *Dependencies, flows Flows, node Node) error {
	// 1. 将节点追加到最近流程的节点列表末尾
	flows.Nearest.Nodes = append(flows.Nearest.Nodes, node)

	// 2. 遍历节点，执行其中的副作用
	err := TraverseNode(Traverser{
		// NodeSimple 处理函数：对每个简单节点执行
		NodeSimple: func(nodeSimple NodeSimple, w *Flow) error {
			// 检查节点是否实现了 EffectGetter 接口
			effectGetter, ok := nodeSimple.(EffectGetter)
			if !ok {
				// 如果没有副作用，直接返回
				return nil
			}

			// 获取节点定义的所有副作用
			effs, err := effectGetter.GetEffects(ctx, deps, flows.Replace(w))
			if err != nil {
				return err
			}

			// 执行每一个 RunEffect 类型的副作用
			for _, eff := range effs {
				if runEff, ok := eff.(RunEffect); ok {
					err = runEff.doNotCallThisDirectly(ctx, deps)
					if err != nil {
						return err
					}
				}
			}
			return nil
		},
		// 注意：意图（Intent）不能有运行时副作用
		// 这里不需要遍历意图
	}, flows.Nearest, &node)

	if err != nil {
		return err
	}

	return nil
}

// MilestoneDoUseUser 是一个里程碑接口
// 里程碑用于标记流程中的重要节点或事件
// 这个特定的里程碑表示"使用用户"，返回用户 ID
//
// 什么是里程碑？想象一下登山途中的标记点：
// 它们帮助你了解进度，记录重要位置
type MilestoneDoUseUser interface {
	Milestone                   // 嵌入式接口：继承 Milestone 的方法
	MilestoneDoUseUser() string // 返回用户 ID
}

// GetUserID 从整个流程中获取用户 ID
// 它会遍历所有节点和意图，查找实现了 MilestoneDoUseUser 接口的元素
//
// 如果发现多个不同的用户 ID，返回 ErrDifferentUserID
// 如果没有找到任何用户 ID，返回 ErrNoUserID
func GetUserID(flows Flows) (userID string, err error) {
	// 遍历整个流程树
	err = TraverseFlow(Traverser{
		// 处理简单节点
		NodeSimple: func(nodeSimple NodeSimple, w *Flow) error {
			// 检查节点是否实现了 MilestoneDoUseUser 接口
			if n, ok := nodeSimple.(MilestoneDoUseUser); ok {
				id := n.MilestoneDoUseUser()
				if userID == "" {
					// 第一次找到用户 ID
					userID = id
				} else if userID != "" && id != userID {
					// 发现不同的用户 ID，这是一个错误（可能是安全问题）
					return ErrDifferentUserID
				}
			}
			return nil
		},
		// 处理意图（Intent）
		Intent: func(intent Intent, w *Flow) error {
			// 检查意图是否实现了 MilestoneDoUseUser 接口
			if i, ok := intent.(MilestoneDoUseUser); ok {
				id := i.MilestoneDoUseUser()
				if userID == "" {
					// 第一次找到用户 ID
					userID = id
				} else if userID != "" && id != userID {
					// 发现不同的用户 ID
					return ErrDifferentUserID
				}
			}
			return nil
		},
	}, flows.Root)

	// 如果没有找到任何用户 ID
	if userID == "" {
		err = ErrNoUserID
	}

	if err != nil {
		return
	}

	return
}
