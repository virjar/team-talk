# 构建和发布
teamTalk是一个从软件产品层面考虑的项目，所以teamTalk的构建以``zip``压缩包形式作为产出物。具体表现为：

- 脚本目录
  - 提供window、Linux平台的启动脚本
  - 自动升级软件脚本（从因体官方存储下载更新包）
  - 重启和守护
- 配置目录：所有可以提供给用户直接修改，或者定制参数的部分
  - 数据库，项目定制化配置等
  - 文档页面内容
  - 网页静态资源
  - 内置资产下载：如前端项目工程、文档工程，其他需要给用户提供下载的内置资源等
- 资产目录：teamTalk发布后，给用户提供部署相关支持资源
  - 数据库建表脚本
  - 其他
- 日志目录：软件运行时日志出现在本目录
- lib：所有java依赖库，teamTalk业务代码编译结果
- storage：存储目录，如果teamTalk使用了某些文件，内嵌数据库，索引等资产

可以看到，和微服务模式相比，作为给客户使用的外部产品，teamTalk提供了更多的修改范围。所以teamTalk的构建和微服务单一文件发布产品项目，会不太一样。

## 开始
在生产构建时，我们提供了一个支持完整的python脚本:``build.py``，他提供对所有模块的编译、打包、优化、部署、发布等流程工作。

**最开始我们使用的纯bash shell实现此脚本，但是shell脚本表达能力比较差，开源版本重构为python实现，故此脚本在未来一段时间还会持续完善修改**
```shell
yint@Mac-Pro team-talk-backend % ./script/build.py -h
usage: build.py [-h] [-v] [-sp] [-npm] {assemble,docker,deploy,all} ...

teamTalk build script

optional arguments:
  -h, --help            show this help message and exit
  -v, --verbose         verbose output
  -sp, --skip-proguard  if skip proguard, the output binary will be debug mode,this only suitable for iinti env
  -npm, --use-npm       use npm as node builder, and the script will use yarn to compile frontend by default

actions:
  {assemble,docker,deploy,all}
    assemble            assemble distribution
    docker              create docker distribution
    deploy              deploy task, with -h argument to get more detail: "build.py deploy -h"
    all                 process all task: assemble,docker,oss,online server
yint@Mac-Pro team-talk-backend % 
```

## 构建
使用assemble命令进行项目打包，assemble将会完成如下流程：

- 后端代码编译
- 前端代码编译
- 文档编译：md转化为html
- 植入前端资源到发布包，给用户下载定制使用：即copy前端工程源码，并压缩植入到发布包、copy文档源码，并压缩植入到发布包
- 调用代码加密工具链，完成对软件产品的方案保护：仅限因体内部可用，开源工程不需要此功能
- 其他相关配套处理：如一些开发环境文件到发布包的微调转换，git提交commit、编译机器、编译环境信息植入，相关脚本赋权等。

assemble指令产生的极为完整的软件发布包，并以zip格式产出。
```shell
./script/build.py assemble
```

## docker
使用docker命令，则完成docker相关镜像打包上传任务，此命令依赖assemble结果。在docker指令执行时，如发现没有assemble结果，
则会主动调用assemble。

**docker命令要求用户主机安装了docker环境，并且完成您指定的docker hub的登录。**

之后脚本更具assemble发布包产生对应的docker镜像，并且将进行推送到远端docker hub，这样最终客户可以使用docker命令一键启动我们的软件产品。


## deploy
deploy则是进行软件的发布部署，即将软件发布到服务器上，在服务器运行软件产品。deploy指令区分多种模式,如下：

```shell
yint@Mac-Pro team-talk-backend % ./script/build.py deploy -h  
usage: build.py deploy [-h] [-d] [-f] [-o]

optional arguments:
  -h, --help      show this help message and exit
  -d, --doc       only deploy doc
  -f, --frontend  only deploy frontend
  -o, --oss       only deploy oss
yint@Mac-Pro team-talk-backend % 
```

由于deploy是将资源发布到服务器，所以需要存在服务器的配置

- 在build.py中有服务器列表，可以指定多台服务器，用户按需修改
- deploy大量使用ssh、scp等命令，这要求用户完成对远端服务器的**免密码登录**，若用户不清楚，则建议先行查找资料
- 一般情况下，要求免密码登录后的用户为root权限，否则首次部署脚本可能会尝试创建相关工作目录，root避免无权限操作

### 仅发布文档
``./script/build.py deploy -d`` 此命令只编译文档内容，并且将文档内容同步到服务器的文档html目录，进行文档更新。
但不会影响其他模块，也不会重启服务器。

**此过程不会进行全量assemble，如果您只希望更新文档内容，则此命令可以很快完成文档发布**
### 仅发布前端
``./script/build.py deploy -f`` 此命令只编译前端网站页面，并且将网页文件同步到服务器的静态网页目录，进行前端页面更新。
但不会影响其他模块，也不会重启服务器。

**此过程不会进行全量assemble，如果您只希望更新前端内容，则此命令可以很快完成前端发布**
### 仅发布oss
``./script/build.py deploy -o`` oss是指发布包静态存储服务器，即将软件安装包放到静态服务器上，供用户下载。

- 由于软件安装包是软件产品形态，所以此命令会触发assemble，进行整体软件打包。
- 此流程不会替换服务器上的软件，也不会重启服务器

### 发布所有
``./script/build.py deploy`` 即没有参数的发布，他有如下动作

- 探测是否存在assemble结果，并在没有结果是调用assemble
- 将assemble结果文件上传到oss服务器
- 将assemble发布到线上所有服务器
  - 在服务器对应工作目录进行解压升级，完成代码升级替换（同步替换了文档、前端）
  - 上传发布版本配置到线上配置目录
  - 重启线上服务器

## all指令
``./script/build.py all`` 此命令进行所有更新，包括本地构建、所有服务器更新升级、docker镜像更新、oss静态存储更新

