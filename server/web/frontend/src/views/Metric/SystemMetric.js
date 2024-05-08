import React from 'react';

import MetricPage from './MetricPage'

const systemMQL = [
    {
        title: "looper线程大盘",
        mql: `# looper线程
looperTaskQueueSize = metric(looper.taskQueueSize);
looper线程积压量 = aggregate(looperTaskQueueSize,'serverId','name');

looperTime = metric(looper.time);
线程 = aggregate(looperTime,'serverId','name');
show(looper线程积压量,线程);
        `
    }, {
        title: "线程池大盘",
        bottomLegend: true,
        mql: `
        
# 线程池
threadPoolCount = metric(thread.pool.count);
threadPoolCount = aggregate(threadPoolCount,'serverId','name');
任务拒绝 = threadPoolCount[type='reject']
任务异常 = threadPoolCount[type='exception']
show(任务拒绝,任务异常);

threadPoolGauge =  metric(thread.pool.gauge)
threadPoolGauge = aggregate(threadPoolGauge,'serverId','name');
核心线程量 = threadPoolGauge[type='coreSize']
活跃量 = threadPoolGauge[type='activeCount']
排队任务量 = threadPoolGauge[type='queueSize']
show(核心线程量,活跃量,排队任务量)

threadPoolTime =  metric(thread.pool.timer)
threadPoolTime = aggregate(threadPoolTime,'serverId','name');
任务执行量 = threadPoolTime[timer_type='count']
最长任务时间 =  threadPoolTime[timer_type='max']
平均任务时间 =  threadPoolTime[timer_type='time'] / 任务执行量
show(任务执行量,最长任务时间,平均任务时间)
        `
    }, {
        title: "磁盘",
        mql: `
# 磁盘
可用空间 = metric(disk.free);
总空间 = metric(disk.total);

可用空间 = aggregate(可用空间,'serverId','path'); 
总空间 = aggregate(总空间,'serverId','path')

磁盘使用率 = ((总空间 - 可用空间) * 100) / 总空间;

可用空间 = 可用空间/1048576; 
总空间 = 总空间/1048576; 

show(可用空间,总空间,磁盘使用率);

        `
    }, {
        title: "负载（Load）",
        mql: `
服务器分钟负载 = metric(system.load.average.1m);
服务器分钟负载 = aggregate(服务器分钟负载,'serverId')
show(服务器分钟负载);

系统CPU使用率 = metric(system.cpu.usage);
系统CPU使用率 = aggregate(系统CPU使用率,'serverId')
进程CPU使用率 = metric(process.cpu.usage);
进程CPU使用率 = aggregate(进程CPU使用率,'serverId')
show(系统CPU使用率,进程CPU使用率);
        `
    }, {
        title: "jvm内存",
        bottomLegend: true,
        mql: `
        jvm内存 = metric(jvm.memory.used);
jvm内存 = aggregate(jvm内存,'area','serverId');
show(jvm内存);
        `
    }
]

const SystemMetrics = () => {
    return (<div>
        <p>请注意：为避免系统指标过多污染，系统监控应该根据业务倾向选择更加应该关注的指标，所以本处没有展示所有的系统指标情况，
            更加详细的指标请通过mql编辑器手动查看</p>
        <MetricPage configs={systemMQL}/>
    </div>);
}

export default SystemMetrics;