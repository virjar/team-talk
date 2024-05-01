import React, {useEffect, useState} from 'react';
import {Button, Card, CardContent, CardHeader, Divider, MenuItem, Select} from "@mui/material";
import MetricCharsV2 from "components/MetricCharts";
import CodeMirror from "@uiw/react-codemirror";
import {createUseStyles} from "react-jss";


const useStyles = createUseStyles(theme => ({
    root: {
        padding: theme.spacing(3)
    },
    content: {
        marginTop: theme.spacing(2)
    },
    item: {
        marginTop: theme.spacing(5)
    },
    tableButton: {
        marginRight: theme.spacing(1)
    }
}));

const demoMQL = `
# 负载
服务器分钟负载 = metric(system.load.average.1m);
show(服务器分钟负载);

系统CPU使用率 = metric(system.cpu.usage);
进程CPU使用率 = metric(process.cpu.usage);
show(系统CPU使用率,进程CPU使用率);
`;

const MQLViewer = () => {
    const classes = useStyles();
    const [accuracy, setAccuracy] = useState("minutes");

    const [errMsg, setErrMsg] = useState("请输入MQL脚本");
    const [mql, setMql] = useState("");


    let initMQL = localStorage.getItem("ViewMQLScript") || demoMQL;
    const [editMql, setEditMql] = useState(initMQL);
    useEffect(() => {
        localStorage.setItem("ViewMQLScript", editMql);
    }, [editMql])


    return (<div className={classes.root}>
            <Card>
                <CardHeader
                    action={
                        (<>
                            <Select
                                className={classes.tableButton}
                                style={{width: "200px", height: "40px", overflow: "hidden"}}
                                variant="outlined"
                                value={accuracy}
                                onChange={(e) => {
                                    setAccuracy(e.target.value);
                                }}
                            >
                                {["minutes", "hours", "days"].map(d => (
                                    <MenuItem key={d} value={d}>
                                        {d}
                                    </MenuItem>
                                ))}
                            </Select>
                            <Button className={classes.tableButton} variant="contained" color="primary"
                                    onClick={() => {
                                        setMql(editMql)
                                        setErrMsg("");
                                    }}
                            >确认</Button>
                            <Button className={classes.tableButton} variant="contained" color="primary"
                                    onClick={() => {
                                        setEditMql(demoMQL)
                                    }}
                            >加载默认MQL</Button>
                        </>)
                    }
                />
                <CardContent className={classes.content}>
                    <CodeMirror
                        height="200px"
                        value={editMql}
                        onChange={(value) => {
                            setEditMql(value);
                        }}
                    />

                    <Divider/>
                    {
                        !!errMsg ? <CodeMirror
                            height="200px"
                            value={errMsg}
                        /> : (
                            <MetricCharsV2
                                onLoadMsg={setErrMsg}
                                className={classes.marginTop}
                                title={"MQL调试指标"}
                                accuracy={accuracy}
                                mql={mql}/>
                        )
                    }

                </CardContent>
            </Card>
        </div>
    );
}

export default MQLViewer;