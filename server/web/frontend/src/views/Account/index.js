import React, {useCallback, useContext, useState} from 'react';
import {OpeDialog, SimpleTable} from "components";
import {AppContext} from "adapter";
import {Button, Grid, TextField, Typography} from "@mui/material";
import {DirectionsRailway, PermIdentity, SupervisorAccount} from "@mui/icons-material";
import config from "../../config";
import moment from "moment/moment";
import {useHistory} from "react-router-dom";
import EmojiPeopleIcon from "@mui/icons-material/EmojiPeople";
import Permission from "./Permission";
import {createUseStyles} from "react-jss";

const LOGIN_USER_MOCK_KEY = config.login_user_key + "-MOCK";

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
    dialogInput: {
        width: "100%"
    }
}));

const CreateUserDialog = (props) => {
    const {openCreateUserDialog, setOpenCreateUserDialog, setRefresh} = props;
    const classes = useStyles();
    const {api} = useContext(AppContext);
    const [account, setAccount] = useState("");
    const [password, setPassword] = useState("");

    return (<OpeDialog
        title="添加用户"
        opeContent={(
            <>
                <Grid
                    container
                    spacing={6}
                    wrap="wrap"
                >
                    <Grid
                        item
                        xs={6}
                    >
                        <Typography
                            gutterBottom
                            variant="h6"
                        >
                            账号
                        </Typography>
                        <TextField
                            className={classes.dialogInput}
                            size="small"
                            variant="outlined"
                            value={account}
                            onChange={(e) => setAccount(e.target.value)}/>
                    </Grid>
                    <Grid
                        item
                        xs={6}
                    >
                        <Typography
                            gutterBottom
                            variant="h6"
                        >
                            密码
                        </Typography>
                        <TextField
                            className={classes.dialogInput}
                            size="small"
                            variant="outlined"
                            type="password"
                            value={password}
                            onChange={(e) => setPassword(e.target.value)}/>
                    </Grid>
                </Grid>
            </>
        )}
        openDialog={openCreateUserDialog}
        setOpenDialog={setOpenCreateUserDialog}
        doDialog={() => {
            return api.userAdd({
                userName: account,
                password: password
            }).then(res => {
                if (res.status === 0) {
                    setRefresh(+new Date());
                }
            });
        }}
        okText="保存"
        okType="primary"/>);
}


const AccountList = () => {

    const {api, setUser} = useContext(AppContext);
    const history = useHistory();
    const classes = useStyles();

    const [openCreateUserDialog, setOpenCreateUserDialog] = useState(false);

    const [permOpAccount, setPermOpAccount] = useState({});
    const [showPermOpDialog, setShowPermOpDialog] = useState(false);
    const [refresh, setRefresh] = useState(+new Date());


    const doLogin = (item) => {
        api.login({
            userName: item.userName,
            password: item.password
        }).then(res => {
            if (res.status === 0) {
                api.setStore({...res.data, mock: true}, LOGIN_USER_MOCK_KEY);
                setUser({
                    ...res.data,
                    mock: true,
                    time: moment(new Date()).format("YYYY-MM-DD HH:mm:ss")
                });
                history.push("/");
            }
        });
    };

    const grantAdmin = (item) => {
        api.grantAdmin({
            userName: item.userName,
            isAdmin: !item.isAdmin
        }).then(res => {
            if (res.status === 0) {
                setRefresh(+new Date());
            }
        });
    };


    const loadApi = useCallback(() => {
        return new Promise((resolve, reject) => {
            api.userList({page: 1, pageSize: 1000})
                .then(res => {
                    if (res.status === 0) {
                        resolve({
                            data: res.data.records,
                            status: 0
                        });
                        return
                    }
                    reject(res.message)
                })
                .catch((e) => {
                    reject(e)
                })
            ;
        })
    }, [api])

    return (
        <div>
            <SimpleTable
                refresh={refresh}
                actionEl={(<Button
                    startIcon={<EmojiPeopleIcon/>}
                    color="primary"
                    variant="contained"
                    onClick={() => setOpenCreateUserDialog(true)}
                >
                    添加用户
                </Button>)}
                loadDataFun={loadApi}
                columns={[
                    {
                        label: "账号",
                        key: "userName"
                    }, {
                        label: "密码",
                        key: "password"
                    }, {
                        label: "管理员",
                        render: (item) => (
                            item.isAdmin ? (<p>是</p>) : (<p>否</p>)
                        )
                    }
                    , {
                        label: '余额',
                        key: 'balance'
                    }, {
                        label: '已充值',
                        key: 'actualPayAmount'
                    }, {
                        label: "操作",
                        render: (item) => (
                            <>
                                <Button
                                    startIcon={<DirectionsRailway style={{fontSize: 16}}/>}
                                    size="small"
                                    color="primary"
                                    className={classes.tableButton}
                                    onClick={() => doLogin(item)}
                                    variant="contained">登录</Button>
                                <Button
                                    startIcon={<PermIdentity style={{fontSize: 16}}/>}
                                    size="small"
                                    color="primary"
                                    className={classes.tableButton}
                                    onClick={() => {
                                        setPermOpAccount(item);
                                        setShowPermOpDialog(true);
                                    }}
                                    variant="contained">配置权限</Button>
                                <Button
                                    startIcon={<SupervisorAccount style={{fontSize: 16}}/>}
                                    size="small"
                                    color="primary"
                                    className={classes.tableButton}
                                    onClick={() => grantAdmin(item)}
                                    variant="contained">{item.isAdmin ? "移除管理员" : "升级管理员"}</Button>
                            </>
                        )
                    }
                ]}
            />

            <CreateUserDialog
                openCreateUserDialog={openCreateUserDialog}
                setOpenCreateUserDialog={setOpenCreateUserDialog}
                setRefresh={setRefresh}
            />


            <OpeDialog title={"编辑权限:" + permOpAccount.userName} okText={"确认"} openDialog={showPermOpDialog}
                       fullScreen
                       setOpenDialog={setShowPermOpDialog}
                       opeContent={
                           (<Permission account={permOpAccount}
                                        setRefresh={setRefresh}
                           />)
                       }
            />
        </div>
    )
}

export default AccountList;