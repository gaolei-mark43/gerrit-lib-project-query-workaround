# gerrit-lib-project-query-workaround

Gerrit 3.6.1 `/admin/repos` 项目列表查询分页重复问题的最小化 HTTP Filter workaround。

## 目标

在不修改 Gerrit WAR、项目 ACL、Git 仓库和 Lucene 索引的前提下，只处理 Gerrit 项目列表 REST 请求：

```text
GET /projects/?n=26&S=0&query=state:active OR state:read-only
```

当请求满足配置条件时，将下游 Gerrit REST API 看到的 `n` 提升到指定值（默认 `100`）。

该组件使用 Gerrit 官方 `httpd.filterClass` 扩展点，JAR 部署到 `$GERRIT_SITE/lib`。

## 当前部署版本

- Workaround: `0.1.0`
- 目标 Gerrit: `3.6.1`
- Java: `11`
- Servlet API: `javax.servlet` 3.1
- 部署形态: `$GERRIT_SITE/lib/*.jar`
- 运行模式: `off` / `dry-run` / `rewrite`

> 测试环境缺少生产仓库和权限数据，因此测试环境只验证 Gerrit 可正常启动、Filter 可加载、请求可命中。`rewrite` 对重复展示的实际效果必须在生产用 `dry-run -> rewrite` 的方式在线验证。

## 请求匹配范围

默认只处理同时满足以下条件的请求：

1. HTTP Method 为 `GET`。
2. Request URI 以 `/projects/` 结尾。
3. `query` 精确等于 `state:active` 或 `state:active OR state:read-only`。
4. `S` 等于配置的 `matchOffset`，默认 `0`。
5. `n > 0` 且 `n <= maxOriginalN`，默认 `99`。

其他 Gerrit 请求直接透传。

## 配置文件

复制：

```bash
cp config/project-query-workaround.properties.example \
  $GERRIT_SITE/etc/project-query-workaround.properties
```

推荐首次部署配置：

```properties
mode=dry-run
targetN=100
maxOriginalN=99
matchOffset=0
responseHeader=true
```

参数说明：

| 参数 | 默认值 | 说明 |
|---|---:|---|
| `mode` | `off` | `off` 完全关闭；`dry-run` 只记录命中；`rewrite` 实际重写 `n` |
| `targetN` | `100` | Gerrit REST 实际收到的 `n` |
| `maxOriginalN` | `99` | 只处理原始 `n` 小于等于该值的请求 |
| `matchOffset` | `0` | 只处理指定 `S` 偏移；首屏为 `0` |
| `responseHeader` | `true` | 命中时返回调试 Header |

配置文件在每次命中的 `/projects/` 请求时重新读取。因此修改 `mode`、`targetN` 等参数后，无需重启 Gerrit；下一次匹配请求立即生效。

## 构建

```bash
mvn clean package
```

产物：

```text
target/gerrit-lib-project-query-workaround-0.1.0.jar
```

## Gerrit 部署

假设：

```bash
GERRIT_SITE=/root/gerrit/review_site/review_site
```

### 1. 部署 JAR

```bash
cp target/gerrit-lib-project-query-workaround-0.1.0.jar \
  $GERRIT_SITE/lib/
```

### 2. 部署运行配置

```bash
cp config/project-query-workaround.properties.example \
  $GERRIT_SITE/etc/project-query-workaround.properties
```

首次保持：

```properties
mode=dry-run
```

### 3. 配置 Gerrit Filter

编辑：

```text
$GERRIT_SITE/etc/gerrit.config
```

增加：

```ini
[httpd]
    filterClass = com.sungrow.gerrit.projectquery.ProjectQueryWorkaroundFilter

[filterClass "com.sungrow.gerrit.projectquery.ProjectQueryWorkaroundFilter"]
    configPath = /root/gerrit/review_site/review_site/etc/project-query-workaround.properties
```

`configPath` 必须使用当前 Gerrit Site 的绝对路径。

如果 `[httpd]` 已存在，只增加一条 `filterClass`，不要覆盖原有 `httpd` 配置。

### 4. 重启 Gerrit

首次安装 JAR / Filter 需要重启：

```bash
cd $GERRIT_SITE
./bin/gerrit.sh restart
```

检查：

```bash
tail -200 logs/error_log
```

确认没有 `ProjectQueryWorkaroundFilter` 加载异常。

## 测试环境验证

保持：

```properties
mode=dry-run
```

打开 Gerrit `/admin/repos`，在浏览器 F12 Network 查看 `/projects/` 请求。

命中时 Response Header 会出现：

```text
X-Sungrow-Gerrit-ProjectQuery-Workaround: dry-run;n=26->100
```

同时 Gerrit 日志会记录命中信息。`dry-run` 不改变请求参数。

测试环境需要确认：

- Gerrit 可以正常启动。
- 登录、Change 页面等普通功能正常。
- `/projects/` 请求可以正常返回。
- 命中的请求存在 workaround Response Header。

## 生产验证流程

生产首次部署仍使用：

```properties
mode=dry-run
```

重启 Gerrit并确认服务正常后：

1. 使用可稳定复现仓库重复的账号打开 `/admin/repos`。
2. 确认 Response Header 为 `dry-run;n=26->100`。
3. 修改配置文件：

```properties
mode=rewrite
```

4. 不重启 Gerrit，刷新 `/admin/repos`。
5. 确认 Header 变为：

```text
X-Sungrow-Gerrit-ProjectQuery-Workaround: rewrite;n=26->100
```

6. 验证重复仓库是否消失、列表数量是否正确、翻页行为是否正常。

## 快速关闭

发现任何异常时直接修改：

```properties
mode=off
```

下一次匹配请求立即停止处理，无需重启 Gerrit。

## 完整卸载

先设置 `mode=off`，确认业务正常，再执行完整卸载：

1. 从 `gerrit.config` 删除：

```ini
filterClass = com.sungrow.gerrit.projectquery.ProjectQueryWorkaroundFilter
```

以及对应的：

```ini
[filterClass "com.sungrow.gerrit.projectquery.ProjectQueryWorkaroundFilter"]
    configPath = ...
```

2. 删除 JAR：

```bash
rm -f $GERRIT_SITE/lib/gerrit-lib-project-query-workaround-0.1.0.jar
```

3. 重启 Gerrit。

## 工作链路

```text
Browser
  |
  | GET /projects/?n=26&S=0&query=...
  v
ProjectQueryWorkaroundFilter
  |
  | off      -> 原请求透传
  | dry-run  -> 记录命中，原请求透传
  | rewrite  -> HttpServletRequestWrapper 将 n 改为 targetN
  v
Gerrit REST /projects/
```

## 边界

该项目是针对当前 Gerrit 3.6.1 线上现象的 workaround，不修改 Gerrit 数据，不执行 reindex，不改 ACL，不触碰 Git 仓库对象。

生产 `rewrite` 开启后仍需重点观察 `/admin/repos` 的列表数量与分页行为。确认 workaround 稳定后再长期启用。
