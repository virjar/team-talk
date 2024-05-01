import React, {useEffect, useState} from "react";
import {Paper, Tab, Tabs} from "@mui/material";

import GlobalMetric from "./GlobalMetric";
import SystemMetrics from "./SystemMetric";
import MQLViewer from "./MQLViewer";
import MetricList from "./MetricList";
import configs from 'config'
import {createUseStyles} from "react-jss";

const useStyles = createUseStyles(theme => ({
    root: {
        flexGrow: 1,
        padding: theme.spacing(3)
    },
    content: {
        marginTop: theme.spacing(2)
    }
}));


function TabPanel(props) {
    const {children, value, index} = props;
    return value === index ? children : null;
}

const metricConfigTabKey = configs.app + "-metric-tab";

function Metrics() {
    const classes = useStyles();


    let initValue = Number(localStorage.getItem(metricConfigTabKey)) || 0;
    const [value, setValue] = useState(initValue);
    useEffect(() => {
        localStorage.setItem(metricConfigTabKey, value + "");
    }, [value])

    const handleChange = (event, val) => {
        setValue(val);
    };

    return (
        <div className={classes.root}>
            <Paper>
                <Tabs
                    value={value}
                    indicatorColor="primary"
                    textColor="primary"
                    onChange={handleChange}
                >
                    <Tab label="业务大盘"/>
                    <Tab label="系统监控"/>
                    <Tab label="MQL编辑器"/>
                    <Tab label="指标列表"/>
                </Tabs>
                <div className={classes.content}>
                    <TabPanel value={value} index={0}>
                        <GlobalMetric/>
                    </TabPanel>
                    <TabPanel value={value} index={1}>
                        <SystemMetrics/>
                    </TabPanel>
                    <TabPanel value={value} index={2}>
                        <MQLViewer/>
                    </TabPanel>
                    <TabPanel value={value} index={3}>
                        <MetricList/>
                    </TabPanel>
                </div>
            </Paper>
        </div>
    );
}

export default Metrics;
