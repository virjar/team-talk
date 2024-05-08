import React, {useContext, useEffect, useState} from "react";
import clsx from "clsx";
import PropTypes from "prop-types";
import DetailsIcon from "@mui/icons-material/Details";
import DeleteIcon from '@mui/icons-material/Delete';
import {
    Button,
    Card,
    CardActions,
    CardContent,
    CardHeader,
    MenuItem,
    Pagination,
    Select,
    Typography
} from "@mui/material";
import {MetricCharsV2, OpeDialog, Table} from "components";
import {AppContext} from "adapter";
import {createUseStyles} from "react-jss";

const useStyles = createUseStyles(theme => ({
    root: {},
    content: {
        padding: 0
    },
    nameContainer: {
        display: "flex",
        alignItems: "center"
    },
    avatar: {
        marginRight: theme.spacing(2)
    },
    actions: {
        paddingTop: theme.spacing(2),
        paddingBottom: theme.spacing(2),
        justifyContent: "center"
    },
    tableButton: {
        marginRight: theme.spacing(1)
    },
    groupButton: {
        border: '1px dashed #f0f0f0',
        marginRight: theme.spacing(1),
        textTransform: "none"
    },
    groupButtonActive: {
        border: '1px dashed #2196f3',
        backgroundColor: '#2196f3',
        marginRight: theme.spacing(1),
        textTransform: "none"
    },
}));

const MetricChart = (props) => {
    const {showMetric, height} = props;
    const classes = useStyles();
    const [accuracy, setAccuracy] = useState("minutes");
    const [aggregateTags, setAggregateTags] = useState([]);
    const [availableTags, setAvailableTags] = useState([]);

    useEffect(() => {
        setAggregateTags([]);
        setAvailableTags((function () {
            let av = [];
            for (let val of arguments) {
                val && av.push(val);
            }
            return av;
        })(showMetric.tag1Name, showMetric.tag2Name, showMetric.tag3Name,
            showMetric.tag4Name, showMetric.tag5Name));
    }, [showMetric])

    return (<Card>
        <CardHeader
            action={
                (<>
                    {availableTags.map(item => (
                        <Button
                            key={item}
                            size="small"
                            onClick={() => {
                                if (aggregateTags.includes(item)) {
                                    setAggregateTags([...aggregateTags].filter(f => f !== item));
                                } else {
                                    setAggregateTags([...aggregateTags, item]);
                                }
                            }}
                            className={aggregateTags.includes(item) ? classes.groupButtonActive : classes.groupButton}>
                            <Typography variant="subtitle2"
                                        style={{color: aggregateTags.includes(item) ? '#fff' : '#546e7a'}}>
                                {item}
                            </Typography>
                        </Button>
                    ))}
                    <Select
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
                </>)
            }
        />
        <CardContent>
            <MetricCharsV2
                height={height}
                title={showMetric.name}
                mql={aggregateTags.length > 0 ?
                    `var = metric('${showMetric.name}'); 
                     var = aggregate(var,${aggregateTags.map(it => '\'' + it + '\'').join(',')}); 
                     show(var); `
                    : `var = metric('${showMetric.name}'); show(var); `
                }
                bottomLegend
                accuracy={accuracy}/>

        </CardContent>
    </Card>);
}


const DataTable = props => {
    const {className, data, total, rowsPerPage, pageState, setRefresh, ...rest} = props;
    const [page, setPage] = pageState;
    const classes = useStyles();
    const {api} = useContext(AppContext);
    const [openDialog, setOpenDialog] = useState(false);
    const [showMetric, setShowMetric] = useState({});

    const [confirmDelete, setConfirmDelete] = useState('');
    const [openDeleteConfirmDialog, setOpenDeleteConfirmDialog] = useState(false);

    const handlePageChange = (event, page) => {
        setPage(page);
    };

    const deleteMetric = (name) => {
        return api.deleteMetric({
            metricName: name
        }).then(res => {
            if (res.status === 0) {
                api.successToast("操作成功");
                setRefresh(+new Date());
            }
        });
    }

    return (
        <Card
            {...rest}
            className={clsx(classes.root, className)}
        >
            <CardContent className={classes.content}>
                <Table
                    data={data}
                    columns={[
                        {
                            label: "指标名称",
                            key: "name"
                        }, {
                            label: "tag1",
                            key: "tag1Name"
                        }, {
                            label: "tag2",
                            key: "tag2Name"
                        }, {
                            label: "tag3",
                            key: "tag3Name"
                        }, {
                            label: "tag4",
                            key: "tag4Name"
                        }, {
                            label: "tag5",
                            key: "tag5Name"
                        }, {
                            label: "操作",
                            render: (item) => (
                                <>
                                    <Button
                                        startIcon={<DeleteIcon style={{fontSize: 16}}/>}
                                        size="small"
                                        color="primary"
                                        onClick={() => {
                                            setConfirmDelete(item.name);
                                            setOpenDeleteConfirmDialog(true);
                                        }}
                                        className={classes.tableButton}
                                        variant="contained">删除</Button>
                                    <Button
                                        startIcon={<DetailsIcon style={{fontSize: 16}}/>}
                                        size="small"
                                        color="primary"
                                        onClick={() => {
                                            setShowMetric(item)
                                            setOpenDialog(true)
                                        }}
                                        className={classes.tableButton}
                                        variant="contained">查看指标</Button>
                                </>
                            )
                        }
                    ]}
                />
            </CardContent>
            <CardActions className={classes.actions}>
                <Pagination
                    count={Math.ceil(total / rowsPerPage) || 1}
                    page={page}
                    onChange={handlePageChange}
                    shape="rounded"/>

            </CardActions>
            <OpeDialog
                fullWidth
                maxWidth={'lg'}
                title={"查看指标" + showMetric.name}
                opeContent={(<MetricChart
                    showMetric={showMetric}
                    height={'500px'}
                />)}
                openDialog={openDialog}
                setOpenDialog={setOpenDialog}
                okText="确认"
                okType="primary"/>
            <OpeDialog
                title={"确认删除指标"}
                opeContent={(<>
                    <Typography
                        gutterBottom
                        variant="h6"
                    >指标删除后不可恢复，请确认删除如下指标：</Typography>
                    {confirmDelete}
                </>)}
                doDialog={() => {
                    return deleteMetric(confirmDelete)
                }}
                openDialog={openDeleteConfirmDialog}
                setOpenDialog={setOpenDeleteConfirmDialog}
                okText="确认"
                okType="primary"/>
        </Card>
    );
};

DataTable.propTypes = {
    className: PropTypes.string,
    data: PropTypes.array.isRequired,
    setRefresh: PropTypes.func.isRequired
};

export default DataTable;
