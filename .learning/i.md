1. 数据类型使用规范
1.1 总则
- 优先使用语义明确、最紧凑且可索引的类型，避免使用泛化的 text 存储所有内容。
- 在设计之初就确定列的数据本质（整数/有界字符串/浮点/货币/时间/JSON/二进制），并在 schema 中强制约束（NOT NULL / CHECK / DOMAIN）。
- 在能被索引或参与 join/聚合的字段上选用适合的基础类型以便高效执行（例如用 bigint 而不是 text 存 id，用 timestamptz 而不是 text 存时间）。

1.2 常用标量类型建议
- 整数
  - 使用 integer (32-bit) 或 bigint (64-bit) 基于业务预计规模。推荐主键使用 bigint（或 IDENTITY 序列）。
  - 避免使用 smallint 除非确有存储节省需求并确定不会溢出。
- 浮点与精确数值
  - 精确财务/计量数据使用 numeric(precision, scale)；避免用 float/double 存钱。
  - 仅在近似运算（科学计算、统计）使用 double precision。
- 字符串
  - 对长度有上限的字段使用 varchar(n)；对无上限且大文本才用 text。
  - 避免默认一律用 text，因为会丢失语义并导致缺乏长度校验。
- 时间/日期
  - 推荐使用 timestamptz（含时区）存储时间戳，确保跨时区一致性。
  - 使用 date / time / interval 在语义上明确的场景。
- 布尔、枚举
  - 布尔（boolean）用于真/假语义。
  - 业务上有固定枚举集合时优先使用 Postgres enum（更省空间且约束明确）或小表 + 外键（便于扩展与国际化）。
- 二进制
  - 二进制数据使用 bytea；对于大文件优先考虑对象存储并在数据库中存引用/元数据。
- UUID
  - 在需要跨系统唯一性或非可预测 ID 时使用 UUID（BINARY(16) 风格，Postgres 内置 uuid 类型）。注意索引排序/速度问题（可用 uuid_generate_v4() 或在应用端批量生成）。
- JSON/JSONB
  - 对半结构化数据使用 jsonb（支持索引、查询）。但若字段经常被筛选或聚合，应拆为结构化列保持可索引性。
  - 对大、频繁更新的 JSONB 警惕 TOAST 与写放大，必要时把热字段拆表。

1.3 复合/数组/自定义类型
- 数组类型（int[], text[]）适用于确实语义为集合的列且不常作为查询过滤条件；否则建立关联表。
- 使用 DOMAIN 定义常用限制（如 email_text domain 包含 regex CHECK），提高可读性与复用。
- 当有复杂复合数据结构并用于多处时，考虑创建 composite type 或自定义 base type，但谨慎因兼容/迁移成本高。

1.4 存储与性能考量
- 小心 TOAST：大文本/JSON 会被 TOAST 到外部存储，影响 I/O。对超大列单独拆表以降低行宽。
- 列选择影响行宽：尽量让写密集表每行小且定长，提高缓存命中与并发性能。
- 对频繁作为过滤/排序/聚合的列使用合适索引和 operator class（比如 text 使用 gin_trgm_ops 做模糊搜索）。

1.5 类型声明与更改策略
- 尽量避免频繁修改列类型；若必须更改，采用非破坏性迁移流程：新增列 -> 回填 -> 切换写入 -> 删除旧列。
- 对于需要兼容历史数据的类型转换，事先在预生产全量演练并估算回填时间与锁影响。

2. Trigger / Function 使用规范
2.1 总则：何时使用触发器/函数
- 使用场景（合理）：
  - 强制数据一致性/补充字段（例如自动维护 updated_at）；
  - 数据审计（行级/语句级变更记录）；
  - 复杂约束无法用 CHECK 实现时用 constraint trigger；
  - 将事务内必须完成的轻量化工作封装在 DB 层（非网络 I/O）。
- 避免场景：
  - 将复杂业务逻辑、网络 IO、HTTP 调用、长耗时任务放在触发器；这些应移到异步后台任务或应用层。

2.2 命名与组织
- 函数命名：fn_<schema>_<table>_<purpose>[_v#]，例如 fn_orders_set_updated_at。
- 触发器命名：trg_<table>_<when>_<purpose>，例如 trg_orders_before_insert_set_ts。
- 将触发器函数放在同一 schema（如 db_functions 或 pg_funcs）并给出版本注释与 changelog（便于迁移/回滚）。

2.3 函数语言与属性
- 优先使用 PL/pgSQL（可移植、易维护）；仅在需要时使用 PL/Python/PLV8 等（需审批、安全审计）。
- 明确标注函数的 VOLATILE / STABLE / IMMUTABLE 属性（根据实现决定），错误标注会破坏 planner 优化。
- 对只读函数尽量标注为 STABLE/IMMUTABLE，从而让 planner 做更多优化。
- 对安全敏感或须提升权限的函数使用 SECURITY DEFINER，但必须：
  - 在函数体开头用 SET SESSION AUTHORIZATION 或设置 search_path，避免 search_path 注入；
  - 将函数所有者设为安全角色并最小权限分配。

2.4 触发器类型与粒度
- 优先使用行级触发器（FOR EACH ROW）仅在必须时，否则 prefer statement-level（FOR EACH STATEMENT）以降低频率与开销。
- 对大批量插入/更新要避免逐行触发导致性能瓶颈；若需要批处理，考虑使用 statement trigger 或临时表 + 后处理。
- 若需要保证触发器只在事务提交后才异步处理，采用 LISTEN/NOTIFY + 后台 worker（或者消息队列）来解耦。

2.5 实现细节与最佳实践
- 避免在触发器中执行外部网络 I/O（例如调用外部 API），这将阻塞事务并降低可用性。
- 在触发器内尽量少用子查询/复杂循环，必要时将复杂计算移到存储过程或批处理。
- 对需要返回修改记录的触发器，使用 TG_ARGV 和 TG_OP，并妥善处理 NEW / OLD。
- 使用 explicit column lists（避免 SELECT *）和尽量限制触发器读取列数。
- 在触发器函数中使用 RAISE NOTICE/LOG 进行可控日志记录，但在高 TPS 场景谨慎，以免污染日志。
- 处理异常：在触发函数中捕获已预见的异常并做适当回滚/记录，但不要吞掉严重错误（应 propagate）。
- 性能计量：对触发器可能引入的延迟进行基准测试（基准数据、并发写入场景）。

2.6 部署 / 迁移 / 回滚
- 所有触发器与函数应通过迁移脚本管理（Flyway/Liquibase），并纳入 CI 流程。
- 提交前在预生产运行升级脚本，验证触发器对批量导入的影响。
- 版本化触发器函数（例如 fn_audit_v1、fn_audit_v2）并提供切换策略：先部署新函数但不启用触发器 -> 在低峰切换 -> 验证后移除旧函数。

2.7 示例（推荐模式）
````sql name=fn_set_updated_at.sql
-- name: fn_set_updated_at.sql
-- 说明：在更新时自动设置 updated_at 字段；使用 SECURITY INVOKER 并标注为 VOLATILE
CREATE OR REPLACE FUNCTION db_funcs.fn_set_updated_at()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  -- 仅当存在该列时才赋值（兼容通用触发器）
  IF TG_OP = 'UPDATE' THEN
    IF TG_TABLE_NAME = TG_TABLE_NAME THEN
      NEW.updated_at := now();
    END IF;
  END IF;
  RETURN NEW;
END;
$$;
-- 触发器示例（按表启用）
CREATE TRIGGER trg_orders_before_update_set_ts
  BEFORE UPDATE ON sales.orders
  FOR EACH ROW
  EXECUTE FUNCTION db_funcs.fn_set_updated_at();
````
（说明：示例中的安全细节要根据组织策略完善，如 search_path 固定和函数所有者设置）

````sql name=fn_audit_row.sql
-- name: fn_audit_row.sql
-- 行级审计示例：只记录必要字段，避免在触发器内做时间昂贵的 JSON 聚合
CREATE TABLE audit.change_log (
  id BIGSERIAL PRIMARY KEY,
  table_name text NOT NULL,
  op text NOT NULL,
  changed_at timestamptz NOT NULL DEFAULT now(),
  user_name text,
  row_before jsonb,
  row_after jsonb
);

CREATE OR REPLACE FUNCTION db_funcs.fn_row_audit()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF (TG_OP = 'DELETE') THEN
    INSERT INTO audit.change_log(table_name, op, user_name, row_before)
    VALUES (TG_TABLE_NAME, TG_OP, current_user, to_jsonb(OLD));
    RETURN OLD;
  ELSIF (TG_OP = 'UPDATE') THEN
    INSERT INTO audit.change_log(table_name, op, user_name, row_before, row_after)
    VALUES (TG_TABLE_NAME, TG_OP, current_user, to_jsonb(OLD), to_jsonb(NEW));
    RETURN NEW;
  ELSIF (TG_OP = 'INSERT') THEN
    INSERT INTO audit.change_log(table_name, op, user_name, row_after)
    VALUES (TG_TABLE_NAME, TG_OP, current_user, to_jsonb(NEW));
    RETURN NEW;
  END IF;
  RETURN NULL;
END;
$$;
````
（说明：审计表需考虑分区与清理策略；大量写会影响主库性能，可考虑异步化）

2.8 安全与权限
- 对 SECURITY DEFINER 的函数，显式设置 search_path，避免被利用：
  - 在函数体首行写：PERFORM set_config('search_path','pg_catalog,db_funcs', true);
- 将函数所有者设为最小权限的专用角色（不使用 superuser 作为函数所有者）。
- 拒绝在触发器中使用不受控制的 dynamic SQL（或使用 quote_ident/quote_literal 严格转义）。

3. 禁止的行为（must not / 禁止项）
3.1 数据库设计与访问
- 禁止在生产环境中使用 application 用户作为 superuser。
- 禁止授予对象权限给 PUBLIC（除非确有必要并且被审计）。
- 禁止将敏感凭据（明文密码、API Key）存入数据库脚本或表中；必须使用机密管理器。
- 禁止在数据库层直接存放大文件（BLOB）而不评估对象存储方案（S3/GCS）。
- 禁止在 DDL/配置变更中绕过代码审查（所有 DDL 必须通过迁移工具和 PR 审核）。

3.2 SQL / 查询层
- 禁止在关键性生产 SQL 中使用 SELECT *（明确列名以防列调整破坏应用）。
- 禁止在高并发写场景建立大量索引或复杂表达式索引而未评估写放大。
- 禁止在事务中做外部网络调用（HTTP、外部系统），或在触发器做这些操作。
- 禁止使用不带参数的动态 SQL 插入用户输入（SQL 注入风险）；必须使用参数化或 quote_* 系列函数。
- 禁止滥用 advisory locks 用作长时间分布式锁（应使用成熟的分布式协调工具）。

3.3 触发器/函数
- 禁止在触发器中执行长时间运行操作（长循环、复杂聚合、大量 I/O）。
- 禁止把复杂业务流程放在触发器中（难以测试与回滚）。
- 禁止将函数标注为 IMMUTABLE/STABLE 与实际副作用不符（这会误导优化器）。
- 禁止在函数内部任意修改 search_path 或依赖于不安全的默认 search_path（除非已明确设置并审计）。
- 对第三方或未经审计的 PL 语言如 plpythonu、plv8，禁止在生产中未经审批直接启用。

3.4 运维与配置
- 禁止在没有备份与恢复演练的情况下进行 major upgrade 或删除分区/表。
- 禁止禁用 autovacuum（除非有明确替代方案并记录到变更单）。
- 禁止对生产实例直接做未经 CI 的手工 schema 变更（所有更改应通过迁移工具执行）。
- 禁止关闭 WAL 或不保留 WAL 归档导致无法实现 PITR。

4. 行业参考与规范链接（可作为章末 Reading List）
4.1 官方与权威文档
- PostgreSQL 官方文档（数据类型、函数、触发器、性能调优等）
  - 总览与数据类型: [PostgreSQL: Documentation — Data Types](https://www.postgresql.org/docs/current/datatype.html)
  - PL/pgSQL: [PostgreSQL: PL/pgSQL - SQL Procedural Language](https://www.postgresql.org/docs/current/plpgsql.html)
  - 触发器: [PostgreSQL: Triggers](https://www.postgresql.org/docs/current/trigger-definition.html)
  - 函数创建: [PostgreSQL: CREATE FUNCTION](https://www.postgresql.org/docs/current/sql-createfunction.html)
  - 索引: [PostgreSQL: Indexes](https://www.postgresql.org/docs/current/indexes.html)
  - 性能调优: [PostgreSQL: Performance Tips](https://www.postgresql.org/docs/current/performance-tips.html)

4.2 社区指南 / 最佳实践
- PostgreSQL Wiki: [Best Practices](https://wiki.postgresql.org/wiki/Best_Practices)
- Citus (水平扩展与大表设计): [Citus Data Docs](https://docs.citusdata.com/)
- Patroni（Postgres HA）：[Patroni docs](https://patroni.readthedocs.io/)
- pgBackRest（备份工具）：[pgBackRest](https://pgbackrest.org/)
- Barman（备份与恢复管理）：[Barman](https://www.pgbarman.org/)
- pgaudit（审计插件）：[pgaudit](https://pgaudit.org/)
- pg_stat_statements（慢查询分析）：[pg_stat_statements](https://www.postgresql.org/docs/current/pgstatstatements.html)

4.3 云厂商参考（托管平台实践）
- AWS RDS for PostgreSQL tuning & best practices: [Amazon RDS - PostgreSQL](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/CHAP_PostgreSQL.html)
- Google Cloud SQL for PostgreSQL: [Cloud SQL - PostgreSQL](https://cloud.google.com/sql/docs/postgres)
- Azure Database for PostgreSQL: [Azure PostgreSQL docs](https://learn.microsoft.com/azure/postgresql/)

4.4 合规 / 数据治理参考
- GDPR 概述（欧盟数据保护）：[GDPR](https://gdpr.eu/)
- PCI DSS（支付卡行业数据安全标准）：[PCI Security Standards](https://www.pcisecuritystandards.org/)
- HIPAA（美国医疗信息隐私法律）：[HHS - HIPAA](https://www.hhs.gov/hipaa/index.html)

4.5 书籍与深入资料（行业认可）
- 《PostgreSQL Administration Cookbook》 — 管理实践与运维脚本（多作者）
- 《High Performance PostgreSQL》（业界多篇实践与案例汇总）
- PGCon / PostgresOpen / FOSDEM 的演讲录像与幻灯（可在 YouTube、Conf 站点检索具体主题）

5. 建议的落地步骤（把规范落到执行）
5.1 立项与培训
- 将本规范写入团队 handbook；组织一次工作坊讲解数据类型选择、触发器风险与示例。
5.2 模板与 CI
- 建立 migration 模板（含触发器/函数审计 checklist），在 PR 模板中要求：变更目的、影响行数估算、回填策略、回滚步骤。
5.3 审计与检测
- 在 CI 中运行静态检查（例如检测 SELECT *、检测是否使用 superuser、检测某些危险函数）。
- 定期扫描数据库中不合规用法（如未注释或未版本化的触发器/函数、不安全的 SECURITY DEFINER 函数）。
5.4 演练与监控
- 定期进行恢复演练、触发器性能基准测试与批量导入压力测试。

6. 附：快速核对清单（Checklist）
- 数据类型：每列已指定具体类型并有理由记录（yes/no）
- JSONB：仅用于补充半结构化数据，必要字段以列形式存储（yes/no）
- 函数/触发器：都有迁移脚本、版本号、owner 与 search_path 固定（yes/no）
- 禁止项：是否有审批流程防止手动绕过（yes/no）
- 备份/PITR：已配置并完成恢复演练（yes/no）
- 审计与监控：pgaudit + pg_stat_statements + 报警（yes/no）

结束语
- 我已经把“数据类型使用”、“触发器/函数规范”、以及“禁止行为”和“行业参考”都补充进去了，并按 1., 1.1. 的编号格式组织成可执行条目。
- 如果你愿意，我可以接着：
  1) 基于你当前实例（版本、规模、是否使用云托管/自建、平均 QPS/数据量）给出一份可运行的核对表与示例迁移脚本；或
  2) 把上面的内容生成一份 Markdown 文档模板（含 PR 模板、迁移脚本 stub、CI 静态检查规则）直接提供给你。

你想继续哪一项（1 或 2），或把你的环境情况告诉我我来做第 1 项？