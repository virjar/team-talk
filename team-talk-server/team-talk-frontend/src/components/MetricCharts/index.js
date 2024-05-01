import React, {useCallback, useContext, useEffect, useState} from 'react';
import ReactEcharts from 'echarts-for-react';
import PropTypes from "prop-types";
import clsx from "clsx";
import {AppContext} from "adapter";
import {createUseStyles} from "react-jss";

const buildEchartOption = (title, legends, x, series, bottomLegend) => {
    for (let i = 0; i < series.length; i++) {
        for (let j = 0; j < series[i].data.length; j++) {
            if (series[i].data[j] > 1) {
                series[i].data[j] = parseFloat(series[i].data[j].toFixed(2));
            }
        }
    }

    return {
        title: {
            left: "center",
            text: title
        },
        tooltip: {
            // 内容过多时将 tooltips 以滚动条的形式展示。
            trigger: 'axis',
            axisPointer: { // 坐标轴指示器，坐标轴触发有效
                type: 'shadow' // 默认为直线，可选为：'line' | 'shadow'
            },
            enterable: true, // 鼠标是否可进入提示框浮层中
            hideDelay: 200, // 浮层隐藏的延迟
            confine: true,
            backgroundColor: 'rgba(255,255,255, 1)',
            formatter: function (params) {
                var htmlStr = '<div style="height: auto;max-height: 240px;overflow-y: auto;"><p>' + params[0].axisValue + '</p>';
                for (var i = 0; i < params.length; i++) {
                    htmlStr += '<p style="color: #666;">' + params[i].marker + params[i].seriesName + ':' + params[i].value + '</p>';
                }
                htmlStr += '</div>'
                return htmlStr
            },
            extraCssText: 'box-shadow: 0 0 3px rgba(150,150,150, 0.7);',
            textStyle: {
                fontSize: 12,
                color: '#fff',
                align: 'left'
            }
        },
        legend: bottomLegend ? {
            orient: "horizontal",
            x: "center",
            top: '65%',
            data: legends
        } : {
            orient: "vertical",
            left: "right",
            bottom: "middle",
            data: legends
        },
        xAxis: {
            type: "category",
            boundaryGap: false,
            data: x
        },
        dataZoom: [
            {
                start: 0,
                end: 100
            }
        ],
        yAxis: {
            type: "value"
        },
        grid: bottomLegend ? {
            bottom: '35%',
            containLabel: true
        } : {},
        series: series
    };
};


const useStyles = createUseStyles(theme => ({
    root: {
        textAlign: "center"
    },
}));

const MetricCharsV2 = (props) => {
    const {
        height = (props.bottomLegend ? '350px' : '300px'),
        title,
        mql,
        accuracy,
        onLoadMsg,
        bottomLegend,
        className
    } = props;
    const {api} = useContext(AppContext);
    const classes = useStyles();
    const [echartOption, setEchartOption] = useState(buildEchartOption(title, [], [], [], bottomLegend));

    const fuckCacheEvent = useCallback(() => {
        // fuck cache
        // ReactEcharts有bug，当option修改时，曲线可能有残留，
        // 挂载一个onEvents空函数，则会触发图标重新渲染
        return typeof echartOption;
    }, [echartOption]);


    useEffect(() => {
        if (!mql || !accuracy) {
            return;
        }
        api.mqlQuery({
                mqlScript: mql,
                accuracy: accuracy
            }
        ).then((res) => {
            if (res.status === 0) {
                setEchartOption(buildEchartOption(title, res.data['legends'], res.data['xaxis'], res.data['series'], bottomLegend));
            }
            if (onLoadMsg) {
                onLoadMsg(res.message)
            }
        });

    }, [bottomLegend, onLoadMsg, mql, accuracy, title, api]);

    return (
        <div className={clsx(classes.root, className)}>
            <ReactEcharts
                onEvents={fuckCacheEvent}
                option={echartOption}
                style={{width: '100%', height}}/>
        </div>
    );

}


MetricCharsV2.propTypes = {
    height: PropTypes.string,
    mql: PropTypes.string.isRequired,
    accuracy: PropTypes.string.isRequired,
    onLoadMsg: PropTypes.func,
    legend: PropTypes.object,
    bottomLegend: PropTypes.bool
};

export default MetricCharsV2;