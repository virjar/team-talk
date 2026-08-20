# 通知机器人接入

通知机器人用于让 CI、监控、审批和发布系统主动向 TeamTalk 群发送 Markdown。
它是一个单向、最小权限的入站通知接口，不是可以接收消息并自主对话的完整客户端。

> 页面中的“入站通知 URL”和一次性 `ttb_...` token 都由 TeamTalk 生成。
> 群成员不需要、也不应该在 TeamTalk 中填写第三方 API 地址、AI 服务密钥或普通用户 token。

## 1. 适用场景

适合使用通知机器人的场景：

- GitHub Actions、Jenkins 等发送构建和发布结果；
- 监控系统发送告警和恢复通知；
- 审批、工单或业务流程同步状态；
- 外部系统以明确的业务事件向固定群发送文本通知。

以下需求不应使用通知机器人：

- 需要收取消息、识别上下文并自动回复；
- 需要主动查询联系人、历史消息或操作群成员；
- 需要发送附件、图片或执行多步 IM 业务流。

这些场景应选择 [ImBot 或 tt-agent](headless.md)。

## 2. 群成员创建并添加机器人

当前产品工作流以群为权限边界：

1. 当前群成员打开目标群，进入“群设置”。
2. 选择“机器人”，点击“添加机器人”。
3. 输入便于群成员识别的名称，例如“发布通知”或“生产告警”。
4. 创建成功后，页面会显示已经绑定当前群的入站通知 URL 和以 `ttb_` 开头的 Bearer token。
5. 立即把 URL 和 token 保存到调用系统的密钥库。确认已安全保存后再关闭凭据弹窗。

token 只在创建或轮换时显示一次。服务端只保存不可恢复的哈希；遗失后无法查看原 token，
只能轮换凭据。

所有当前群成员都可以创建通知机器人。创建者可以轮换自己机器人的凭据，也可以把自己的机器人从
当前群移除；普通成员不能轮换或移除其他成员创建的机器人。群主和群管理员可以从当前群移除任意
由群成员创建的机器人以治理本群，但不能查看、复制或轮换其他创建者的 token。关闭一次性凭据弹窗
后，即使是创建者也不能重新查看原 token，只能通过轮换生成新 token。

群成员创建的机器人固定属于创建时所在的群，不能再授权给另一个群。由系统管理员下发到群的机器人
会标记为“由系统管理员管理”，群内成员和群管理角色都不能查看、轮换或移除其凭据，应由系统后台
完成治理。

为避免误操作或凭据泄露后批量创建服务身份，每个群最多同时保留 20 个群成员创建的活动机器人；
同一成员在同一群最多创建 5 个、在全部群合计最多管理 50 个活动机器人。移除并停用的机器人不占
活动配额。单节点还限制每位成员每小时最多发起 10 次成功进入创建流程的请求，避免反复创建、移除
造成服务身份堆积。达到配额或速率限制时页面会显示服务端拒绝原因，群成员可先移除不再使用的机器人，
或稍后再试。

## 3. HTTP 请求契约

页面给出的 URL 应当是完整的 TeamTalk HTTPS 地址，形如：

```text
https://im.example.com/api/v1/groups/<chatId>/bots/<botId>/messages
```

URL 已经绑定目标群和机器人。调用方只需原样保存、调用，不需要另外读取、配置或提交群 ID，也不应
自行拼接路径。

调用方在 HTTP Header 中携带 Bot token，不得把 token 拼进 URL 或 query string：

```http
POST /api/v1/groups/<chatId>/bots/<botId>/messages HTTP/1.1
Host: im.example.com
Authorization: Bearer ttb_<only-shown-once>
Content-Type: application/json

{
  "markdown": "## 构建完成\n\n版本 `1.2.3` 已发布。"
}
```

成功返回 HTTP 200：

```json
{ "ok": true }
```

字段约束：

| 字段 | 约束 |
|---|---|
| `markdown` | 非空，最多 20,000 个字符 |

单节点默认对每个机器人限制 120 次/分钟。调用方应对 429 和 5xx 做有上限的退避；如果业务不能
接受网络超时后的潜在重复通知，请使用后文的“高级重试”机制。

## 4. Markdown 内容

通知正文走 TeamTalk 的统一 Markdown 消息模型，可使用：

- 标题、段落和换行；
- 粗体、斜体、删除线和行内代码；
- 有序、无序和任务列表；
- 引用、代码块、链接和 GFM 表格。

详细展示语义见[富文本与媒体](rich-content.md)。不要依赖原始 HTML 或脚本执行；通知端点只承诺按
TeamTalk 支持的 Markdown 展示，也不接收远程文件 URL 或附件字节。需要文件、图片、回复或其他交互时，
使用 ImBot/tt-agent 的正式附件与消息接口。

## 5. curl 示例

先从密钥管理系统注入 URL 和 token。本地临时验收时，可用隐藏输入读取 token，
避免它进入 shell 历史：

```bash
export TEAMTALK_BOT_URL='https://im.example.com/api/v1/groups/<chatId>/bots/<botId>/messages'
read -rsp 'TeamTalk bot token: ' TEAMTALK_BOT_TOKEN
echo

curl --fail-with-body --silent --show-error \
  --request POST "$TEAMTALK_BOT_URL" \
  --header "Authorization: Bearer $TEAMTALK_BOT_TOKEN" \
  --header 'Content-Type: application/json' \
  --data "$(jq -n \
    --arg markdown $'## 构建完成\n\n版本 `1.2.3` 已发布。' \
    '{markdown: $markdown}')"

unset TEAMTALK_BOT_TOKEN
```

## 6. Python 示例

token 从运行环境的密钥注入，不写入源码：

```python
import json
import os
import urllib.request

url = os.environ["TEAMTALK_BOT_URL"]
token = os.environ["TEAMTALK_BOT_TOKEN"]

payload = json.dumps({
    "markdown": "## 构建完成\n\n版本 `1.2.3` 已发布。",
}).encode("utf-8")

request = urllib.request.Request(
    url,
    data=payload,
    method="POST",
    headers={
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
    },
)

with urllib.request.urlopen(request, timeout=15) as response:
    result = json.load(response)
    if result != {"ok": True}:
        raise RuntimeError(f"unexpected TeamTalk response: {result}")
    print("TeamTalk notification delivered")
```

## 7. GitHub Actions 示例

在 GitHub 仓库或 Environment 中配置：

- Variable `TEAMTALK_BOT_URL`：页面给出的完整入站通知 URL；
- Secret `TEAMTALK_BOT_TOKEN`：一次性 `ttb_...` token。

```yaml
name: notify-teamtalk

on:
  workflow_run:
    workflows: [build]
    types: [completed]

jobs:
  notify:
    runs-on: ubuntu-latest
    steps:
      - name: Send TeamTalk notification
        env:
          TEAMTALK_BOT_URL: ${{ vars.TEAMTALK_BOT_URL }}
          TEAMTALK_BOT_TOKEN: ${{ secrets.TEAMTALK_BOT_TOKEN }}
          RESULT: ${{ github.event.workflow_run.conclusion }}
          RUN_URL: ${{ github.event.workflow_run.html_url }}
        run: |
          markdown="$(printf '## Build %s\n\nRun: %s' "$RESULT" "$RUN_URL")"
          payload="$(jq -n \
            --arg markdown "$markdown" \
            '{markdown: $markdown}')"
          curl --fail-with-body --silent --show-error \
            --request POST "$TEAMTALK_BOT_URL" \
            --header "Authorization: Bearer $TEAMTALK_BOT_TOKEN" \
            --header 'Content-Type: application/json' \
            --data "$payload"
```

URL 自身已经确定接收群，因此 GitHub 中不需要再维护群 ID。默认示例只发送一次，避免在结果不明的
网络超时后自动重复通知；需要自动重试时，使用下一节的高级机制。

## 8. 高级重试（可选）

普通调用不需要幂等参数。只有调用方会在网络超时、429 或 5xx 后自动重试，并且不能接受重复通知时，
才应额外发送 `Idempotency-Key` Header：

```http
Idempotency-Key: deploy-production-1.2.3
```

key 长度为 1–120 个字符，应由调用方使用稳定的业务事件 ID 生成。同一业务事件的所有尝试必须使用
相同 URL、相同 key 和完全相同的 Markdown；服务端会把它们识别为同一次投递。同一个 key 如果配上
不同正文会被拒绝。新的业务事件必须使用新 key。

不发送该 Header 时，每个请求都是一次独立通知。`Idempotency-Key` 只用于重试去重，不代替 Bot token，
也不能改变 URL 已经绑定的目标群。单节点每机器人 120 次/分钟的限制仍会计算所有请求尝试。

## 9. 错误码与处理

| HTTP 状态 | 含义 | 调用方处理 |
|---|---|---|
| `200` | 消息已被 TeamTalk 接受，响应为 `{ "ok": true }` | 结束调用或重试 |
| `400` | Markdown、可选幂等 Header 或其他请求参数不合法 | 修正请求，不要盲目重试 |
| `401` | token 缺失、错误、已轮换，或机器人已停用 | 核对凭据和机器人状态 |
| `403` | URL 对应机器人未获目标群授权，或当前不允许发送 | 请创建者、群管理员或群主检查机器人状态，不要继续重试 |
| `429` | 单节点每 bot 120 次/分钟限制已触发 | 有上限地退避；启用高级重试时保持原 key 和正文 |
| `5xx` | TeamTalk 服务暂时失败 | 有上限地退避；启用高级重试时保持原 key 和正文 |

错误响应使用 `{ "error": "..." }`。不要依赖自然语言错误文案做程序分支，应以 HTTP 状态为主。

## 10. 凭据轮换、移除与泄露处置

### 轮换凭据

机器人创建者在自己机器人的详情中选择“轮换凭据”后：

1. 旧 token 立即失效；
2. 新 token 只显示一次；
3. 调用方应先更新密钥库，再使用新 token 做一次受控验收。

轮换会立即替换该群机器人的唯一有效 token。群主和群管理员不能轮换其他成员创建的机器人；需要
立即阻断它对本群的访问时，应先从当前群移除，再联系创建者处置凭据。

### 从群移除

创建者可以移除自己的机器人；群主和群管理员可以移除当前群中由群成员创建的任意机器人。移除后，
它被停用，对当前群的发送授权立即失效，机器人服务身份同时移出当前群。普通成员不能移除其他成员
创建的机器人；由系统管理员下发的机器人只能在系统后台管理。

群被解散或部门群被停用时，服务端会先撤销该群的全部机器人授权；群成员创建且归属于该群的机器人
同时停用。即使部门群以后使用同一稳定 ID 重新启用，旧 token 也不会自动恢复发送权限。

### token 可能泄露

创建者发现 token 可能泄露时，不要先删日志或等待调用方发版。应立即轮换 token，再更新调用方密钥、
检查最近机器人消息，并从历史、日志、截图和代码仓库中清理泄露值。群主或群管理员无法代替创建者
查看或轮换 token；在等待创建者处置期间，应先把该机器人从当前群移除。

## 11. 安全边界

- 只通过 HTTPS 向入站通知 URL 发送 Bot token。
- token 只放在 `Authorization` Header，不放在 URL、代码、日志、报错或截图中。
- 群成员创建的机器人固定授权给创建时所在群；URL 中的目标群由服务端生成并校验，请求正文不能
  指定或改写目标。系统下发机器人同样必须具备服务端显式群授权。
- 入站通知 URL 中的 chatId 和 botId 都不是密钥；即使知道 URL，没有 token 和群授权也不能发送。
- 不得使用管理员凭据、普通用户 access/refresh token 或 tt-agent token 调用通知端点。
- 单节点限速不是跨节点防滥用系统；多节点共享配额和持久化调用审计仍是后续能力。

### 凭据对照

| 凭据 | 签发方 | 用途 | 是否可用于通知端点 |
|---|---|---|---|
| `ttb_...` Bot token | TeamTalk 机器人创建/轮换流程 | 外部系统向已授权群发通知 | **是** |
| 管理后台 token | TeamTalk 管理员登录 | 管理 API | 否 |
| 用户 access/refresh token | TeamTalk 用户会话 | IM 连接、续期与文件访问 | 否 |
| tt-agent API token | 本机 tt-agent | 本机 loopback REST | 否 |
| AI/云服务 API Key | 第三方服务 | 调用第三方产品 | 否 |

## 12. 与 ImBot / tt-agent 的区别

| 能力 | 通知机器人 | ImBot | tt-agent |
|---|---|---|---|
| 主要方向 | 外部系统 → TeamTalk | 双向 | 双向 |
| 连接 | 每次 HTTP 请求 | 常驻 TeamTalk 客户端连接 | 常驻 ImBot + 本地 REST |
| 凭据 | 一次性签发的 `ttb_...` token | TeamTalk 客户端身份 | 客户端身份 + 本机 API token |
| 权限 | 只能向显式授权群发 Markdown | 与登录身份的 IM 权限相同 | 与内部 ImBot 身份相同 |
| 收消息/查历史 | 不支持 | 支持 | 支持 |
| 典型场景 | CI、监控、审批 | Kotlin 自动化应用 | CLI、脚本、MCP/AI 工具 |

tt-agent 的 `127.0.0.1:8600 + agent-api-token` 只是调用本机守护进程的地址和凭据，不是 TeamTalk
入站通知 URL，也不应暴露到公网。
