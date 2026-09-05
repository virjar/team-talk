import fs from "node:fs/promises";
import path from "node:path";
import { pathToFileURL } from "node:url";

// Regenerate with Codex's bundled workspace runtime:
// TEAMTALK_ARTIFACT_NODE_MODULES="$RUNTIME_NODE_MODULES" "$RUNTIME_NODE" \
//   tools/presentations/build-why-teamtalk.mjs
//
// The deck is maintained as a template-following artifact. By default the
// current PPTX is imported and normalized in place. Set
// TEAMTALK_WHY_TEAMTALK_TEMPLATE to apply the same edits to another inspected
// copy of the 12-slide TeamTalk template. For a new design pass, point it at
// the validated template-starter.pptx produced by the template-following tools.

const artifactNodeModules = process.env.TEAMTALK_ARTIFACT_NODE_MODULES;
if (!artifactNodeModules) {
  throw new Error("TEAMTALK_ARTIFACT_NODE_MODULES must point to the bundled Node.js packages directory");
}

const artifactToolUrl = pathToFileURL(
  path.join(artifactNodeModules, "@oai/artifact-tool/dist/artifact_tool.mjs"),
).href;
const { FileBlob, PresentationFile } = await import(artifactToolUrl);

const finalPath = path.resolve("doc/assets/presentations/why-teamtalk.pptx");
const templatePath = path.resolve(process.env.TEAMTALK_WHY_TEAMTALK_TEMPLATE ?? finalPath);

const sourceLine = "来源：厂商官网与 TeamTalk 仓库文档；核对于 2026-09-02";
const teamTalkSourceLine = "来源：TeamTalk 产品、架构与测试文档；核对于 2026-09-02";

const slides = [
  {
    texts: [
      "TEAMTALK / BUSINESS POSITIONING",
      "TeamTalk\n不是第四个飞书",
      "它是一条第三路径：\n让组织用可承担的成本，拥有自己的协作系统。",
      "面向企业经营者、政企客户与交付伙伴",
      "第三条路径",
      "完全开源",
      "私有部署",
      "标准内核",
      "深度定制",
      "可接手",
      "AI 原生",
    ],
    sources: [
      "TeamTalk repository: README.md",
      "TeamTalk repository: LICENSE",
      "TeamTalk repository: doc/02-product/why-teamtalk.md",
    ],
    notes: "开场不要比较菜单数量。核心命题是：通用 SaaS 与重型专属工程之间，是否能出现一套标准化、完全开源、可长期接手的第三路径。TeamTalk 尚未正式发布，本页表达的是产品定位。",
  },
  {
    texts: [
      "TEAMTALK / WHY US",
      "市场真正缺少的，不是另一套 SaaS",
      "02",
      "而是通用 SaaS 与重型专属工程之间的第三条路径",
      "成熟 SaaS",
      "功能完整、上线快；\n组织接受平台边界",
      "TeamTalk",
      "标准化私有产品；\n组织拥有系统",
      "即开即用",
      "长期可拥有",
      sourceLine,
    ],
    sources: [
      "https://www.feishu.cn/service?tab=free",
      "https://www.dingtalk.com/",
      "https://work.weixin.qq.com/",
      "TeamTalk repository: doc/02-product/why-teamtalk.md",
    ],
    notes: "这不是排行榜。成熟 SaaS 服务绝大多数标准需求；TeamTalk 只在控制权、深度改造和长期接手的价值高于自维护成本时成立。",
  },
  {
    texts: [
      "TEAMTALK / WHO BUYS",
      "三类组织，三种购买逻辑",
      "03",
      "小团队",
      "优先用成熟免费版",
      "标准需求、无需运维；\n通常不是 TeamTalk 客户",
      "中型组织",
      "TeamTalk 早期主场",
      "数据已成资产；需要深改，\n但养不起专属产品团队",
      "大型 / 政企",
      "控制与接手优先",
      "受控网络、系统集成；\n通过伙伴完成现场交付",
      "人数不是唯一标准：真正的分界是标准需求，还是必须拥有、深改并长期接手系统。",
      teamTalkSourceLine,
    ],
    sources: [
      "TeamTalk repository: doc/02-product/why-teamtalk.md",
      "https://www.gjbmj.gov.cn/n1/2024/0227/c409088-40184579.html",
      "https://www.gjbmj.gov.cn/n1/2021/1224/c441634-32316231.html",
    ],
    notes: "小团队通常应直接用成熟免费产品。早期主市场是数十到千人左右、已形成数据和行业流程但无法承担重型专属工程的组织。真正涉密项目必须依法定级并由有资质伙伴承接，TeamTalk 当前不能宣称涉密就绪。",
  },
  {
    texts: [
      "TEAMTALK / FAIR COMPARISON",
      "先承认：成熟产品在各自战场上更强",
      "04",
      "飞书",
      "知识协作",
      "消息、文档、会议、日历、\n多维表格与知识库一体化",
      "钉钉",
      "组织数字化",
      "组织、人事、审批、考勤、\n低代码、行业生态与 AI",
      "企业微信",
      "连接微信",
      "内部协同与微信客户、\n伙伴和服务网络连接",
      "TeamTalk 的成立不依赖贬低竞品，而依赖解决它们没有必要为每个客户深改的问题。",
      sourceLine,
    ],
    sources: [
      "https://www.feishu.cn/",
      "https://open.feishu.cn/",
      "https://www.dingtalk.com/",
      "https://open.dingtalk.com/",
      "https://www.tencent.net.cn/zh-cn/products/wecom/",
    ],
    notes: "飞书、钉钉的开放平台和 AI 工程都很成熟；钉钉有专属/专有形态，飞书也披露过大型银行本地化案例。差异不是‘竞品不能做’，而是完整源码、运行栈和演进权是否作为标准边界交给客户。",
  },
  {
    texts: [
      "TEAMTALK / DELIVERY ECONOMICS",
      "大型专属方案，成本往往消耗在协作边界",
      "05",
      "01",
      "销售售前",
      "反复解释需求",
      "02",
      "架构路由",
      "拆解与派单",
      "03",
      "多团队开发",
      "跨边界协同",
      "04",
      "回归发布",
      "扩大影响面",
      "05",
      "长期运维",
      "专属环境成本",
      "这不是某家厂商的成本披露，而是公共平台复杂度进入单一客户交付后的结构性推断。",
      sourceLine,
    ],
    sources: [
      "TeamTalk repository: doc/02-product/why-teamtalk.md",
      "https://www.feishu.cn/content/wechat_post_2054",
      "https://www.on-premises.dingtalk.com/",
    ],
    notes: "不要说竞品一定亏损或代码差。可表达为结构性判断：服务海量多租户的组织与技术复杂度，在专属项目中可能转化为沟通、回归、云资源和长期维护的‘平台复杂度溢价’。",
  },
  {
    texts: [
      "TEAMTALK / PRODUCT MODEL",
      "把 90%—95% 做成标准，把 5%—10% 留给客户",
      "06",
      "90%—95%",
      "稳定、公开、持续升级的\nTeamTalk 标准内核",
      "+",
      "5%—10%",
      "客户配置",
      "系统连接器",
      "行业模块",
      "现场适配",
      "差异留在边缘，共同修复回到主线；避免每个客户都变成无法升级的孤岛。",
      teamTalkSourceLine,
    ],
    sources: [
      "TeamTalk repository: doc/02-product/why-teamtalk.md",
      "TeamTalk repository: doc/03-architecture/README.md",
      "TeamTalk repository: doc/08-development/engineering-rules.md",
    ],
    notes: "90%—95% 不是硬性代码计量，而是治理原则。标准内核吸收通用能力与修复，客户只维护真正独有的配置、连接器和行业模块。这样核心团队、伙伴和客户 IT 才能共同维护。",
  },
  {
    texts: [
      "TEAMTALK / CURRENT PROOF",
      "TeamTalk 已有一条可验证的产品主链",
      "07",
      "01",
      "协作",
      "消息 / 搜索",
      "02",
      "组织资产",
      "组织 / 文档",
      "03",
      "双端",
      "Desktop / Android",
      "04",
      "可靠同步",
      "本地 / 恢复",
      "05",
      "私有交付",
      "部署 / 验收",
      "当前基础足以进入受控 POC；任务、日历、会议、完整 AI 平台和正式发布基线仍待建设。",
      teamTalkSourceLine,
    ],
    sources: [
      "TeamTalk repository: doc/02-product/capabilities.md",
      "TeamTalk repository: doc/10-reference/feature-status.md",
      "TeamTalk repository: doc/09-testing/README.md",
    ],
    notes: "这是第一张技术页，但仍用业务能力表达。当前 IM 主链、组织、搜索、富媒体、Desktop/Android、无头 SDK 与私有部署均有实现；管理后台、办公套件广度、Windows/Linux 同等级验收和生产发布制度尚不成熟。",
  },
  {
    texts: [
      "TEAMTALK / OPEN SOURCE",
      "完全开源：产品免费，专业服务创造收入",
      "08",
      "Apache-2.0 开放内核",
      "客户不按人数购买 TeamTalk",
      "自行部署、修改和分发",
      "选择自己的维护团队",
      "代码、数据和构建可接手",
      "允许商业与闭源衍生",
      "服务与伙伴交付",
      "收费来自承担结果",
      "架构评估与受控 POC",
      "部署、升级与可靠性保障",
      "政企联合投标与二线支持",
      "行业集成和伙伴培训",
      "核心不收费，服务有价格；系统归客户，生态靠选择。",
      "来源：Apache-2.0、TeamTalk 商业路径；核对于 2026-09-02",
    ],
    sources: [
      "https://www.apache.org/licenses/LICENSE-2.0",
      "TeamTalk repository: LICENSE",
      "TeamTalk repository: doc/02-product/why-teamtalk.md",
    ],
    notes: "Apache-2.0 允许使用、修改、商业分发和闭源衍生，并要求保留许可证/归属和标记修改。TeamTalk 早期不收产品或席位费，收入来自咨询、升级方案、可靠性、行业集成和联合交付。第三方组件继续适用各自许可证。",
  },
  {
    texts: [
      "TEAMTALK / AI-NATIVE",
      "AI 的机会：从插件变成组织中的正式协作者",
      "09",
      "今天的基础",
      "AI 已能进入正式通信链",
      "BOT 身份可发送通知",
      "Agent / CLI 可双向通信",
      "共享客户端缓存与恢复",
      "基础 MCP 已映射",
      "下一阶段：AI 员工",
      "身份、工作与责任可治理",
      "进入群组、项目与组织",
      "可 @、可派单、可追踪",
      "模型与数据由客户选择",
      "可停用、交接与接管",
      "竞品也有成熟 Agent；TeamTalk 的机会是让客户拥有 AI 运行栈与协作上下文。",
      sourceLine,
    ],
    sources: [
      "https://www.feishu.cn/content/kdvbmrpn",
      "https://www.dingtalk.com/qidian/page-lrMDiA1S.html",
      "TeamTalk repository: doc/05-clients/notification-bots.md",
      "TeamTalk repository: doc/10-reference/roadmap.md",
    ],
    notes: "不要把飞书或钉钉 AI 说成简单包装。TeamTalk 当前只有通信链基础：AutomationBot 主要单向通知，ImBot/tt-agent 仍借用 HUMAN 账号，MCP 也只是基础映射。AI 员工的一等身份、任务收据、知识访问、模型治理和生命周期属于下一阶段。",
  },
  {
    texts: [
      "TEAMTALK / ECOSYSTEM",
      "商业飞轮建立在采用率，而不是数据窥探",
      "10",
      "01",
      "开放内核",
      "零许可门槛",
      "02",
      "行业共创",
      "客户主动咨询",
      "03",
      "伙伴交付",
      "本地实施",
      "04",
      "应用 / AI 技能",
      "标准化复用",
      "05",
      "交易服务",
      "客户主动选择",
      "我们被邀请坐到行业工作台旁，而不是坐在客户的数据管道里。",
      "来源：TeamTalk 商业与生态路径；核对于 2026-09-02",
    ],
    sources: [
      "TeamTalk repository: doc/02-product/why-teamtalk.md",
    ],
    notes: "行业洞察来自客户主动咨询、联合设计、实施合作和行业交流，不来自读取客户聊天或文档。长期收入可来自应用、AI 岗位技能以及差旅/采购等由客户主动选择的交易服务；官方制定兼容标准，但不强制捆绑。",
  },
  {
    texts: [
      "TEAMTALK / DECISION GATE",
      "用 2—4 周 POC，让证据替代口号",
      "11",
      "1",
      "部署",
      "客户环境从空数据搭建\n确认数据与附件边界",
      "2",
      "双端",
      "Desktop + Android\n完成真实业务链",
      "3",
      "深改",
      "贯通一个行业对象\n模型到 UI 全链验收",
      "4",
      "恢复",
      "只隔离目标端点\n验证缓存、重试与接手",
      "POC 不是证明 TeamTalk 全面获胜，而是确认拥有系统的价值是否大于自维护成本。",
      teamTalkSourceLine,
    ],
    sources: [
      "TeamTalk repository: doc/09-testing/README.md",
      "TeamTalk repository: doc/09-testing/deployment-acceptance.md",
      "TeamTalk repository: doc/02-product/why-teamtalk.md",
    ],
    notes: "POC 选择一个真实工作流，验证部署、双端、深改、故障恢复、数据导出和三年 TCO。断线测试只隔离目标应用端点，绝不关闭测试主机网络。",
  },
  {
    texts: [
      "TEAMTALK / CONCLUSION",
      "不是所有组织\n都需要 TeamTalk",
      "我们只服务那些：\n‘拥有系统’本身就是业务价值的组织。",
      "选择成熟套件，是购买今天已经完善的工作方式；\n选择 TeamTalk，是投资一套可以长期拥有、改造和接手的协作系统。",
      "诚实边界",
      "Apache-2.0 已确定\n\n尚未正式发布\n\n迁移 / 备份待补齐\n\n套件与 AI 仍在建设",
      "完整长文：doc/02-product/why-teamtalk.md",
    ],
    sources: [
      "TeamTalk repository: LICENSE",
      "TeamTalk repository: doc/02-product/why-teamtalk.md",
      "TeamTalk repository: doc/10-reference/roadmap.md",
    ],
    notes: "收尾不要承诺全面替代。标准化需求优先成熟套件；当私有部署、深度改造、受控网络和长期接手本身具有业务价值时，TeamTalk 才值得进入 POC。",
  },
];

function parseInspect(ndjson) {
  return ndjson
    .split(/\r?\n/)
    .filter((line) => line.trim())
    .map((line) => JSON.parse(line));
}

function notesText(sources, presenterNotes) {
  return [
    "[Sources]",
    ...sources.map((source) => `- ${source}`),
    "[/Sources]",
    "",
    "Presenter notes:",
    presenterNotes,
  ];
}

const presentation = await PresentationFile.importPptx(await FileBlob.load(templatePath));
const inspection = parseInspect((await presentation.inspect({
  kind: "slide,textbox,notes",
  maxChars: 1_000_000,
})).ndjson);

const slideRecords = inspection.filter((record) => record.kind === "slide");
const textRecordsBySlide = new Map();
for (const record of inspection.filter((item) => item.kind === "textbox")) {
  const records = textRecordsBySlide.get(record.slide) ?? [];
  records.push(record);
  textRecordsBySlide.set(record.slide, records);
}

if (slideRecords.length !== slides.length) {
  throw new Error(`Expected ${slides.length} template slides, found ${slideRecords.length}`);
}

for (let index = 0; index < slides.length; index += 1) {
  const slideNumber = index + 1;
  const spec = slides[index];
  const textRecords = textRecordsBySlide.get(slideNumber) ?? [];
  if (textRecords.length !== spec.texts.length) {
    throw new Error(
      `Slide ${slideNumber} expected ${spec.texts.length} inherited text shapes, found ${textRecords.length}`,
    );
  }

  for (let textIndex = 0; textIndex < textRecords.length; textIndex += 1) {
    const record = textRecords[textIndex];
    const target = presentation.resolve(record.id);
    if (!target) {
      throw new Error(`Slide ${slideNumber} shape ${record.id} could not be resolved`);
    }
    // Full assignment is intentional: artifact-tool's range replacement does
    // not cross paragraph boundaries, so multiline inherited slots would keep
    // stale copy. The shape-level text style and geometry remain inherited.
    target.text = spec.texts[textIndex];
  }

  const slide = presentation.resolve(slideRecords[index].id);
  slide.speakerNotes.textFrame.setText(notesText(spec.sources, spec.notes));
  slide.speakerNotes.setVisible(true);
}

await fs.mkdir(path.dirname(finalPath), { recursive: true });
const pptx = await PresentationFile.exportPptx(presentation);
await pptx.save(finalPath);

console.log(`Wrote ${finalPath}`);
