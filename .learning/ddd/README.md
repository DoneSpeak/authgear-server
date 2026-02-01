# DDD架构设计文档索引

本目录包含使用领域驱动设计（DDD）方法分析的 Authgear Server 系统设计文档。

## 文档结构

### 总览文档

1. **[DDD总览.md](./DDD总览.md)** - DDD方法概述、领域划分、架构层次说明

### 核心领域模型

2. **[领域模型设计.md](./领域模型设计.md)** - 详细的聚合、实体、值对象、领域服务设计
3. **[聚合关系图.md](./聚合关系图.md)** - 聚合之间的依赖关系图

### 核心领域详细设计（重点文档）

#### 认证流程领域

4. **[认证流程领域设计.md](./认证流程领域设计.md)** - 认证流程的领域模型设计
5. **[认证流程时序图.md](./认证流程时序图.md)** - Signup/Login/Reauth流程时序图
6. **[认证流程状态机.md](./认证流程状态机.md)** - 认证流程状态转换图

#### SSO领域

7. **[SSO领域设计.md](./SSO领域设计.md)** - 单点登录的领域模型设计
8. **[SSO流程时序图.md](./SSO流程时序图.md)** - Browser SSO和Device SSO流程时序图

#### Social Login领域

9. **[Social Login领域设计.md](./Social Login领域设计.md)** - OAuth第三方登录的领域模型设计
10. **[OAuth流程时序图.md](./OAuth流程时序图.md)** - OAuth授权流程时序图

#### Session管理领域

11. **[Session管理领域设计.md](./Session管理领域设计.md)** - 会话管理的领域模型设计
12. **[Session生命周期图.md](./Session生命周期图.md)** - Session创建、更新、销毁流程状态图
13. **[设备管理设计.md](./设备管理设计.md)** - 设备识别和管理设计

#### MFA领域

14. **[MFA领域设计.md](./MFA领域设计.md)** - 多因素认证的领域模型设计
15. **[MFA流程时序图.md](./MFA流程时序图.md)** - MFA验证流程时序图

## 快速开始

### 学习路径

1. **阅读 [DDD总览.md](./DDD总览.md)** - 了解DDD方法和系统整体架构
2. **阅读 [领域模型设计.md](./领域模型设计.md)** - 理解核心领域模型
3. **按需深入特定领域** - 根据学习目标选择重点领域：
   - 认证流程 → [认证流程领域设计.md](./认证流程领域设计.md)
   - SSO → [SSO领域设计.md](./SSO领域设计.md)
   - Social Login → [Social Login领域设计.md](./Social Login领域设计.md)
   - Session管理 → [Session管理领域设计.md](./Session管理领域设计.md)
   - MFA → [MFA领域设计.md](./MFA领域设计.md)

### 查看图表

所有PlantUML图表可以使用以下方式查看：

1. **VS Code插件**: 安装PlantUML插件，打开`.md`文件中的 plantuml 代码块，按`Alt+D`预览
2. **在线工具**: 访问 [PlantUML Online Server](http://www.plantuml.com/plantuml/uml/)，复制 plantuml 代码块内容查看
3. **命令行工具**: 使用PlantUML命令行工具生成图片

## 核心聚合

系统包含以下核心聚合：

1. **User聚合** - 管理用户、身份和认证器
2. **Session聚合** - 管理用户会话和设备
3. **AuthenticationFlow聚合** - 管理认证流程执行
4. **OAuth聚合** - 管理OAuth授权和Social Login
5. **MFA聚合** - 管理多因素认证（作为User的子实体）

## 设计原则

- **聚合边界清晰**: 每个聚合有明确的职责边界
- **聚合根控制**: 所有对聚合的操作都通过聚合根
- **值对象不可变**: 值对象创建后不可修改
- **领域服务无状态**: 领域服务不保存状态
- **事件驱动**: 使用领域事件实现聚合间通信

## 参考资源

- [领域驱动设计 - Eric Evans](https://www.domainlanguage.com/ddd/)
- [实现领域驱动设计 - Vaughn Vernon](https://vaughnvernon.com/implementing-domain-driven-design/)
- [Authgear官方文档](../../docs/)
- [数据库模型文档](../docs/数据库模型总览.md)

---

**最后更新**: 2026-01-28
