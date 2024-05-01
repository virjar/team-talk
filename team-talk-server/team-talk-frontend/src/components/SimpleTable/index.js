import React, {useEffect, useState} from "react";

import clsx from "clsx";
import {SearchInput} from "../index";
import {Card, CardActions, CardContent, Pagination} from "@mui/material";
import Table from "../Table";
import PropTypes from "prop-types";
import {createUseStyles} from "react-jss";

const useStyles = createUseStyles(theme => ({
    root: {
        padding: theme.spacing(3)
    },
    content: {
        marginTop: theme.spacing(2)
    },
    row: {
        height: '100%',
        display: "flex",
        alignItems: "center",
        flexWrap: "wrap",
        marginTop: theme.spacing(1)
    },
    spacer: {
        flexGrow: 1
    },
    tableRoot: {},
    tableContent: {
        padding: 0
    },
    searchInput: {
        marginBottom: theme.spacing(2)
    },
    actions: {
        paddingTop: theme.spacing(2),
        paddingBottom: theme.spacing(2),
        justifyContent: "center"
    },

}));

const Toolbar = props => {
    const {onInputChange, ActionEl} = props;

    const classes = useStyles();

    return (
        <div className={classes.row}>
            <SearchInput
                className={classes.searchInput}
                onChange={(v) => onInputChange(v)}
                placeholder="请输入关键词进行查询"
            />
            <span className={classes.spacer}/>
            {ActionEl ? ActionEl : <></>}
        </div>
    );
};
const DataTable = props => {
    const {className, data, total, rowsPerPage, pageState, setRefresh, columns, renderCollapse, ...rest} = props;
    const [page, setPage] = pageState;

    const classes = useStyles();

    const handlePageChange = (event, page) => {
        setPage(page);
    };

    return (
        <Card
            {...rest}
            className={clsx(classes.tableRoot, className)}
        >
            <CardContent className={classes.tableContent}>
                <Table
                    collapse={!!renderCollapse}
                    renderCollapse={renderCollapse}
                    data={data}
                    columns={columns}
                />
            </CardContent>
            <CardActions className={classes.actions}>
                <Pagination
                    count={Math.ceil(total / rowsPerPage) || 1}
                    page={page}
                    onChange={handlePageChange}
                    shape="rounded"/>
            </CardActions>

        </Card>
    );
};

const SimbleTable = (props) => {
    const classes = useStyles();
    const {loadDataFun, actionEl, columns, refresh, renderCollapse} = props;

    const [data, setData] = useState([]);
    const [page, setPage] = useState(1);
    const [limit] = useState(10);
    const [keyword, setKeyword] = useState("");
    const [innerRefresh, setInnerRefresh] = useState(refresh || +new Date());

    useEffect(() => {
        loadDataFun().then(res => {
            if (res.status === 0) {
                setData(res.data);
            }
        })
    }, [loadDataFun, innerRefresh]);

    useEffect(() => {
        setInnerRefresh(+new Date())
    }, [refresh])

    const showData = data.filter(item => {
        return JSON.stringify(item).includes(keyword);
    });

    return (
        <div className={classes.root}>
            <Toolbar
                onInputChange={(k) => {
                    setKeyword(k);
                    setPage(1);
                }}
                setRefresh={setInnerRefresh}
                ActionEl={actionEl}
            />
            <div className={classes.content}>
                <DataTable
                    renderCollapse={renderCollapse}
                    data={showData.slice((page - 1) * limit, page * limit)}
                    total={showData.length}
                    columns={columns}
                    rowsPerPage={limit}
                    pageState={[page, setPage]}
                    setRefresh={setInnerRefresh}/>
            </div>
        </div>
    );
};

SimbleTable.propTypes = {
    loadDataFun: PropTypes.func.isRequired,
    actionEl: PropTypes.element,
    columns: PropTypes.array.isRequired,
    refresh: PropTypes.number,
    renderCollapse: PropTypes.func
};

export default SimbleTable;
