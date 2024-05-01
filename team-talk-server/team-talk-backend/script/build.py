#!/usr/bin/env python3
import argparse
import getpass
import os
import os.path as op
import re
import shutil
import stat
import subprocess
import sys
import time
from zipfile import ZipFile

# 可能需要根据每个项目不同进行定制化修改的部分
protection_main_jar = "team-talk-service"
protection_save_jars = ['mybatis-plus-extension']

deploy_remote_user = 'root'
deploy_server_list = ['teamTalk.iinti.cn']
deploy_path = '/opt/teamTalk'
# 若存在此配置，则构建脚本支持将发布包上传到oss，给用户下载
deploy_oss_server = 'oss.iinti.cn'
deploy_oss_asset_path = "/root/local-deplpy/gohttpserver/data/teamTalk/"
# 若存在此配置，则构建脚本支持将docker镜像发布到docker仓库
deploy_docker_registry = 'registry.cn-beijing.aliyuncs.com/iinti/common'


# resolve config
def parse_constants(service_module_base):
    config_file = op.join(service_module_base, "src/main/java/com/virjar/tk/server/service/base/env/Constants.java")
    with open(config_file, "r") as file:
        parse_ret = dict()
        for line in file.readlines():
            #  String docPath = "team-talk-doc";
            result = re.search('String\\s*(\\S+?)\\s*=\\s*\"(\\S*?)\"', line)
            if result:
                parse_ret[result.group(1)] = result.group(2)
        return parse_ret


def extract_assemble_app_name(service_module_base):
    config_file = op.join(service_module_base, "pom.xml")
    with open(config_file, "r") as file:
        return re.search('<app\\.name>\\s*(\\S+?)\\s*</app\\.name>', file.read()).group(1)


def color_print(code, msg):
    if no_color:
        print(msg)
    else:
        msg = msg.replace("\n", f"\033[0m\n{code}")
        print(f"{code}{msg}\033[0m")


def error(msg):
    color_print("\033[41;39m", f"\n! {msg}\n")
    sys.exit(1)


def header(msg):
    color_print("\033[44;39m", f"\n{msg}\n")


def vprint(msg):
    if args.verbose:
        print(msg)


def mv(source, target):
    try:
        shutil.move(source, target)
        vprint(f"mv {source} -> {target}")
    except:
        pass


def cp(source, target):
    try:
        if op.exists(target) and op.isdir(target):
            target = op.join(target, op.basename(source))
        shutil.copyfile(source, target)
        vprint(f"cp {source} -> {target}")
    except:
        pass


def mkdir(path, mode=0o755):
    try:
        os.mkdir(path, mode)
    except:
        pass


def execv(cmd, env=None):
    return subprocess.run(cmd, stdout=STDOUT, env=env, check=True)


def cmd_out(cmd, env=None):
    return (
        subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=env)
        .stdout.strip()
        .decode("utf-8")
    )


def execv_npm(cmd, env=None):
    tool_bin = npm_bin if (args.use_npm or not yarn_bin) else yarn_bin
    return execv([tool_bin, *cmd], env=env)


def process_all(_all_args):
    class DockerFuck:
        def __int__(self):
            self.force_assemble = False

    class DeployFuck:
        def __int__(self):
            self.oss = False
            self.doc = False
            self.frontend = False

    run_assemble({})
    run_docker_build(DockerFuck())
    run_deploy(DeployFuck())


def run_deploy(deploy_args):
    header("deploy...")
    if deploy_args.oss:
        archive_file = op.join(service_module, f"target/{config['assemble_app_name']}.zip")
        if not op.exists(archive_file):
            args.skip_proguard = False
            run_assemble([])
        execv(['scp', archive_file,
               f"root@{deploy_oss_server}:{deploy_oss_asset_path}"])
        return

    if deploy_args.doc:
        run_doc_build()
        doc_build_output = op.join(doc_code_dir, "src/.vuepress/dist/.")
        print("begin deploy doc")
        for server_node in deploy_server_list:
            print("deploy "+server_node)
            server_config = f"{deploy_remote_user}@{server_node}:{deploy_path}/conf/static/team-talk-doc/"
            execv(['scp','-r', doc_build_output, server_config])
        print("done")
        return
    if deploy_args.frontend:
        run_frontend_build()
        frontend_code_dir = config['frontend_project']
        frontend_build_output = op.join(frontend_code_dir, 'build/.')
        for server_node in deploy_server_list:
            server_config = f"{deploy_remote_user}@{server_node}:{deploy_path}/conf/static/"
            execv(['scp','-r', frontend_build_output, server_config])
        return

    global assemble_archive_file
    if not assemble_archive_file:
        assemble_archive_file = op.join(service_module, f"target/{config['assemble_app_name']}.zip")
    if not op.exists(assemble_archive_file):
        run_assemble([])

    assemble_archive_file_name = op.basename(assemble_archive_file)
    for server_node in deploy_server_list:
        print(f"do deploy for server:{server_node}")
        execv(['ssh', f"{deploy_remote_user}@{server_node}",
               f"if [[ ! -d \"{deploy_path}\" ]] ; then mkdir  {deploy_path} ; fi"])
        execv(['scp', assemble_archive_file, f"{deploy_remote_user}@{server_node}:{deploy_path}"])

        print("unzip from server")
        execv(['ssh', f"{deploy_remote_user}@{server_node}",
               f"unzip -o -d {deploy_path} {deploy_path}/{assemble_archive_file_name}"])
        execv(['scp', '-r', f"{project_root}/team-talk-service/src/main/resources-env/prod/",
               f"{deploy_remote_user}@{server_node}:{deploy_path}/conf/"])

        print("bootstrap service...")
        cmds = ['ssh', f"{deploy_remote_user}@{server_node}", f"{deploy_path}/bin/startup.sh"]
        subprocess.run(cmds, stdout=None)


def run_docker_build(docker_args):
    """
    本函数给因体内部使用，用于自动发布docker镜像到阿里镜像仓库，
    :param docker_args:
    :return:
    """
    header("run docker build")
    distribution_file = op.join(service_module, f"target/{config['assemble_app_name']}.zip")
    if not op.exists(distribution_file) or docker_args.force_assemble:
        # 生产发布必须走混淆，强制扭转配置
        args.skip_proguard = False
        run_assemble({})
    docker_work_space = op.join(project_root, 'script/docker')
    cp(distribution_file, docker_work_space)
    ddl_file = op.join(docker_work_space, "compose/assets/ddl.sql")
    with ZipFile(distribution_file) as zf:
        with zf.open("assets/ddl.sql") as ddl:
            with open(ddl_file, "wb") as bb:
                bb.write(ddl.read())

    if deploy_oss_server and deploy_oss_asset_path:
        print('copy compose script to file server')
        os.chdir(op.join(docker_work_space, 'compose'))
        execv(['zip', '-f', 'team-talk-compose.zip', '.'])
        execv(['scp', 'team-talk-compose.zip', f"root@{deploy_oss_server}:{deploy_oss_asset_path}"])
        execv(['scp', op.join(iinti_asset_base, 'quickstart.sh'),
               f"root@{deploy_oss_server}:{deploy_oss_asset_path}"])
    os.chdir(docker_work_space)

    print('build docker compose img')
    img_prefix = f"{deploy_docker_registry}:teamTalkserver"
    execv(['docker', 'build', '-t', f"{img_prefix}-{build_time}", '.'])
    execv(['docker', 'tag', f"{img_prefix}-{build_time}", f"{img_prefix}-latest"])
    execv(['docker', 'push', f"{img_prefix}-{build_time}"])
    execv(['docker', 'push', '-t', f"{img_prefix}-latest"])

    print("build docker all in one img")
    img_prefix = f"{deploy_docker_registry}:team-talk-allInOne"
    execv(['docker', 'build', '-f', 'Dockerfile.AllInOne', '-t', f"{img_prefix}-{build_time}", '.'])
    execv(['docker', 'tag', f"{img_prefix}-{build_time}", f"{img_prefix}-latest"])
    execv(['docker', 'push', f"{img_prefix}-{build_time}"])
    execv(['docker', 'push', '-t', f"{img_prefix}-latest"])


def run_frontend_build():
    print("begin build frontend")
    frontend_code_dir = config['frontend_project']
    os.chdir(frontend_code_dir)
    if not op.exists(op.join(frontend_code_dir, "node_modules")):
        print("install frontend dependency, please wait...")
        execv_npm(['install'])
    print("assemble frontend")
    ret = execv_npm(['run', "build"])
    if ret.returncode != 0:
        error("assemble failed")


def run_doc_build():
    print("begin build doc..")
    os.chdir(doc_code_dir)
    if not op.exists(op.join(doc_code_dir, "node_modules")):
        print("install doc dependency, please wait...")
        execv_npm(['install'])
    print("assemble doc")
    ret = execv_npm(['run', "build"])
    if ret.returncode != 0:
        error("assemble failed")


def run_assemble(_args):
    header("begin assemble...")
    # call maven install
    os.chdir(project_root)
    cmds = [mvn, '-Pprod', '-Dmaven.test.skip=true', 'install']
    if not args.verbose:
        cmds.append("-q")
    execv(cmds)

    # assemble core
    print("build core module")
    os.chdir(service_module)
    cmds = [mvn, "-Pprod", "-Dmaven.test.skip=true", "clean", "package", "appassembler:assemble"]
    if not args.verbose:
        cmds.append("-q")
    ret = execv(cmds)
    if ret.returncode != 0:
        error("assemble failed")

    # resolve built-in dir
    assemble_dir = op.join(service_module, f"target/{config['assemble_app_name']}-release")
    assemble_assets_dir = op.abspath(op.join(assemble_dir, "assets"))
    assemble_static_dir = op.join(assemble_dir, "conf/static")
    assemble_doc_dir = op.join(assemble_static_dir, config['docPath'])
    mkdir(assemble_assets_dir)
    mkdir(assemble_static_dir)
    mkdir(assemble_doc_dir)

    print("inject build config")
    commit_hash = cmd_out(["git", "rev-parse", "--short=8", "HEAD"])
    build_config_gen = f"#build info\n" \
                       f"env.buildTime={build_time}\n" \
                       f"env.buildUser={getpass.getuser()}\n" \
                       f"env.gitId={commit_hash}"
    with open(op.join(assemble_dir, f"conf/{config['BUILD_CONFIG_PROPERTIES']}"), "w") as f:
        f.write(build_config_gen)

    print("inject build-in resource")
    cp(op.join(project_root, 'script/assets/startup.sh'), op.join(assemble_dir, "bin/startup.sh"))
    cp(op.join(project_root, 'script/assets/upgrade.sh'), op.join(assemble_dir, "bin/upgrade.sh"))
    cp(op.join(service_module, "script/assets/ddl.sql"), assemble_assets_dir)
    # setup permission
    out_bin_dir = op.join(assemble_dir, "bin")
    for bin_script_name in os.listdir(out_bin_dir):
        if bin_script_name.endswith(".sh"):
            target_file = op.join(out_bin_dir, bin_script_name)
            execv(['chmod', '+x', target_file])

    frontend_code_dir = config['frontend_project']
    if node_bin:
        run_doc_build()
        doc_build_output = op.join(doc_code_dir, "src/.vuepress/dist")
        shutil.copytree(doc_build_output, assemble_doc_dir, dirs_exist_ok=True)

        if op.exists(frontend_code_dir):
            run_frontend_build()
            shutil.copytree(op.join(frontend_code_dir, 'build'), assemble_static_dir, dirs_exist_ok=True)

    print("inject binding frontend resource")
    os.chdir(doc_code_dir)
    execv(['zip', '-r', 'doc-source.zip', '.', '-x', 'node_modules/*', 'src/.vuepress/dist/*',
           'src/.vuepress/.temp/*'])
    mv('doc-source.zip', op.join(assemble_static_dir, 'doc-source.zip'))

    if op.exists(frontend_code_dir):
        os.chdir(frontend_code_dir)
        execv(['zip', '-r', 'frontend-source.zip', '.', '-x', 'node_modules/*', '.idea/*',
               'build/*'])
        mv('frontend-source.zip', op.join(assemble_static_dir, 'frontend-source.zip'))
    has_proguard = False
    if iinti_tool and op.exists(inject_rule_path):
        # 调用因体的混淆工具链，执行加密方案，仅限因体内部业务使用，外部开源不需要加密
        has_proguard = True
        print("merge jar")
        cmds = [iinti_tool, 'MergeJar', '--lib-dir', op.join(assemble_dir, 'lib'), '--main-jar', protection_main_jar,
                '--slave-jar', *protection_save_jars]
        execv(cmds)

        print("prepare proguard")
        cmds = [iinti_tool, 'PrepareProguard', '--config-file', inject_rule_path,
                '--debug', 'false',
                '--input-jar', protection_main_jar]
        execv(cmds)

        if not args.skip_proguard:
            print("do proguard")
            cmds = [iinti_tool, 'PrepareProguard', '--config-file', op.join(iinti_asset_base, 'proguard.pro'),
                    '--jar-file', protection_main_jar,  # ,'--original-mapping'
                    '--mapping', op.join(assemble_dir, 'assets/proguard.map')]
            execv(cmds)
    print("zip binary")
    os.chdir(assemble_dir)
    archive_name = f"{config['assemble_app_name']}{'' if has_proguard else '-no-proguard'}.zip"
    execv(['zip', '-q', '-r', archive_name, '.'])

    tmp_archive = op.join(assemble_dir, archive_name)
    os.chdir('..')
    global assemble_archive_file
    assemble_archive_file = op.abspath(op.join(assemble_dir, f"../{archive_name}"))
    mv(tmp_archive, assemble_archive_file)

    print(f"assemble finished with out file:{assemble_archive_file} ")


is_windows = os.name == "nt"
EXE_EXT = ".exe" if is_windows else ""

no_color = False
if is_windows:
    try:
        import colorama

        colorama.init()
    except ImportError:
        # We can't do ANSI color codes in terminal on Windows without colorama
        no_color = True

# Global vars
STDOUT = None
project_root = op.dirname(op.dirname(op.abspath(__file__)))
service_module = op.join(project_root, "team-talk-service")
doc_code_dir = op.join(project_root, "doc")

mvn = op.join(project_root, "mvnw.cmd" if is_windows else "mvnw")
config = parse_constants(service_module)
build_time = time.strftime('%Y%m%d%H%M', time.localtime(time.time()))
iinti_asset_base = op.join(project_root, 'script/iinti')
inject_rule_path = op.join(iinti_asset_base, 'inject_rule.txt')

# fill default config
config['frontend_project'] = op.abspath(op.join(project_root, "../team-talk-frontend"))
config['assemble_app_name'] = extract_assemble_app_name(service_module)
assemble_archive_file = None

# check toolkit
node_bin = shutil.which("node")
if node_bin:
    node_version = cmd_out([node_bin, "--version"])
    if node_version < "v18.0.0":
        error("the node version must be grater than v18.0.0, now:" + node_version)
        exit(1)
else:
    print("no node env, document and frontend can not build")

# default use yarn replace npm
yarn_bin = shutil.which("yarn")
npm_bin = shutil.which("npm")

iinti_tool = shutil.which("IntTool." + ("bat" if is_windows else "sh"))
if not iinti_tool:
    print("none iinti toolkit present, this must be opensource env, or code protection will be disable")

parser = argparse.ArgumentParser(description="teamTalk build script")
parser.set_defaults(func=lambda x: parser.print_help())
parser.add_argument("-v", "--verbose", action="store_true", help="verbose output")
parser.add_argument(
    "-sp", "--skip-proguard", action="store_true",
    help="if skip proguard, the output binary will be debug mode,this only suitable for iinti env"
)
parser.add_argument(
    "-npm", "--use-npm", action="store_true",
    help="use npm as node builder, and the script will use yarn to compile frontend by default"
)
subparsers = parser.add_subparsers(title='actions')

assemble_parser = subparsers.add_parser('assemble', help="assemble distribution")
assemble_parser.set_defaults(func=run_assemble)

docker_parser = subparsers.add_parser('docker', help="create docker distribution")
docker_parser.add_argument('-f', "--force-assemble",
                           help="force assemble distribution file even through assembled binary file presented")
docker_parser.set_defaults(func=run_docker_build)

deploy_parser = subparsers.add_parser('deploy',
                                      help="deploy task, with -h argument to get more detail: \"build.py deploy -h\"")
deploy_parser.add_argument('-d', "--doc", action="store_true", help="only deploy doc")
deploy_parser.add_argument('-f', "--frontend", action="store_true", help="only deploy frontend")
deploy_parser.add_argument('-o', "--oss", action="store_true", help="only deploy oss")
deploy_parser.set_defaults(func=run_deploy)

all_parser = subparsers.add_parser('all', help="process all task: assemble,docker,oss,online server")
all_parser.set_defaults(func=process_all)

args = parser.parse_args()
STDOUT = None if args.verbose else subprocess.DEVNULL

# Call corresponding functions
args.func(args)
