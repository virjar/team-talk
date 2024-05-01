import _ from 'lodash';

export function initChart() {
    return {
        xAxis: {
            type: 'category',
            data: []
        },
        yAxis: [{
            type: 'value'
        }],
        legend: {
            data: ['value']
        },
        tooltip: {
            trigger: 'axis'
        },
        dataZoom: [
            {
                type: 'inside',
                start: 80,
                end: 100
            },
            {
                start: 80,
                end: 100
            }
        ],
        series: []
    }
}

export function initData() {
    return {
        x: [],
        y: [[], [], []]
    }
};

export function doAnalysis({data, poly, step = 'minutes'}) {
    let steps = {
        'minutes': 60, 'hours': 60 * 60, 'days': 24 * 60 * 60
    }
    // 聚合
    let polyGroupBy = _.groupBy(data, (k) => k.timeKey);
    let polyData = [];
    for (let i = 0; i < Object.keys(polyGroupBy).length; i++) {
        let item = polyGroupBy[Object.keys(polyGroupBy)[i]];
        if (poly) {
            item = _.filter(item, (obj) => {
                let tags = JSON.parse(obj.tags);
                for (let k of Object.keys(poly)) {
                    if (tags[k] !== poly[k]) {
                        return false;
                    }
                }
                return true;
            })
        }
        let res;
        switch (item[0]?.type) {
            case 0:
                let valueBaisc = _.reduce(item, (sum, n) => sum + n.value, 0) / item.length
                res = {
                    timeKey: item[0].timeKey,
                    tags: item[0].tags,
                    type: item[0].type,
                    valueBaisc,
                    value: (valueBaisc - (polyData[i - 1]?.valueBaisc || 0)) / steps[step]
                };
                break;
            case 1:
                res = {
                    timeKey: item[0].timeKey,
                    tags: item[0].tags,
                    type: item[0].type,
                    value: _.reduce(item, (sum, n) => sum + n.value, 0) / item.length,
                    max: _.reduce(item, (max, n) => Math.max(max, n.max), 0),
                };
                break;
            case 3:
                let totalBaisc = _.reduce(item, (sum, n) => sum + n.total, 0)
                res = {
                    timeKey: item[0].timeKey,
                    tags: item[0].tags,
                    type: item[0].type,
                    value: _.reduce(item, (sum, n) => sum + n.value, 0) / item.length,
                    max: _.reduce(item, (max, n) => Math.max(max, n.max), 0),
                    totalBaisc,
                    total: (totalBaisc - (polyData[i - 1]?.totalBaisc || 0)) / steps[step]
                };
                break;
            default:
                break;
        }
        polyData.push(res);
    }
    // 数据类型分组
    let groupBy = _.groupBy(_.drop(polyData), (k) => {
        return {'0': 'counter', '1': 'gauge', '3': 'timer'}[k.type]
    });
    let result = initData();
    // 遍历所有分组
    Object.keys(groupBy).map(k => {
        // 数据分片
        // let chunk = _.chunk(groupBy[k], step)
        // 每个片段执行特定函数
        // _.invokeMap(chunk, function () {
        //   // x 轴取 step 中第一条数据时间
        //   result[k].x.push(this[0].timeKey)
        //   // counter y 轴
        //   if (k === 'counter') {
        //     result[k].y[0].push(_.reduce(this, (sum, n) => sum + n.value, 0) / step)
        //   }
        // })
        // counter
        if (k === 'counter') {
            _.forEach(groupBy[k], (item) => {
                result.x.push(item.timeKey)
                result.y[0].push(item.value)
            });

        }
        // gauge
        if (k === 'gauge') {
            _.forEach(groupBy[k], (item) => {
                result.x.push(item.timeKey)
                result.y[0].push(item.value)
                result.y[1].push(item.max)
            });
        }
        // timer
        if (k === 'timer') {
            _.forEach(groupBy[k], (item) => {
                result.x.push(item.timeKey)
                result.y[0].push(item.value)
                result.y[1].push(item.max)
                result.y[2].push(item.total)
            });
        }
        return true
    })

    return result;
}