---
home: true
title: teamTalk
icon: fa6-solid:house
heroImage: /images/logo.svg
actions:

- text: 序言
  link: /01_preamble/
  type: primary
- text: 开发者文档
  link: /02_developer/
  type: secondary

highlights:

- header: 简单
  description: 我们重视给软件开发这客户的使用体验，在各个阶段考量用户的心智负担
  features:
    - title: 零依赖
      details:  所有公共能力均内嵌实现，无外部依赖（无外部仓库、外部资源、外部服务）
    - title: 零中间件
      details:  不依赖prometheus、redis、nacos、elk、oss等进程级别的三方服务，系统零配置一键启动 
    - title: 研发内聚
      details: teamTalk运行起来几乎零配置，用户很容易一键运行开发环境。同时可以一键完成生成打包
    - title: 代码结构精简
      details: 不墨迹，不过度设计，用户很容易看懂项目

- header: 完善
  description: teamTalk实现了一个完整软件产品必要的大多数公共能力，同时使用了极为少量的代码
  features:
    - title: 中间件生态
      details: 配置中心、监控体系、用户体系、日志等公司级别功能能力
    - title: 前后端联动
      details:  使用前后分离架构，同时实现前后端框架。demo即具备完整的前端页面体系
    - title: 文档
      details:  基于vuepress的文档系统、基于swagger的在线接口
    - title: 脚手架
      details:  构建脚本、发布脚本、代码生成、项目重命名
---
<div id="docNotice"></div>

## 开源地址

- [github](https://github.com/yint-tech/teamTalk)