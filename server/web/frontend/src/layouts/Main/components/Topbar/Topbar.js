import React, {useContext} from 'react';
import {Link as RouterLink, useHistory} from 'react-router-dom';
import {AppBar, Hidden, IconButton, Toolbar, Typography} from '@mui/material';
import {AppContext} from 'adapter';
import clsx from 'clsx';
import PropTypes from 'prop-types';
import MenuIcon from '@mui/icons-material/Menu';
import InputIcon from '@mui/icons-material/Input';
import EmojiNatureIcon from '@mui/icons-material/EmojiNature';
import MenuBookIcon from '@mui/icons-material/MenuBook';
import GitHubIcon from '@mui/icons-material/GitHub';
import config from 'config'
import Notice from "../../../Notice";
import {createUseStyles} from "react-jss";


const LOGIN_USER_MOCK_KEY = config.login_user_key + "-MOCK";

const useStyles = createUseStyles(theme => ({
    root: {
        boxShadow: 'none'
    },
    title: {
        fontWeight: 'bold',
        fontSize: 24,
        color: '#fff'
    },
    flexGrow: {
        flexGrow: 1
    },
    signOutButton: {
        marginLeft: theme.spacing(1)
    },
    download: {
        padding: theme.spacing(2),
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center'
    },
    downloadA: {
        color: theme.palette.primary.main,
        fontSize: 12,
        marginTop: theme.spacing(1),
        marginBottom: -theme.spacing(1),
        textDecoration: 'none'
    }
}));

const Topbar = props => {
    const {user, api, setUser} = useContext(AppContext);
    const {className, onSidebarOpen, ...rest} = props;
    const history = useHistory();
    const classes = useStyles();

    const onLogout = () => {
        localStorage.removeItem(config.login_user_key);
        history.push("/sign-in");
    }

    const onMockOut = () => {
        localStorage.removeItem(LOGIN_USER_MOCK_KEY);
        setUser(api.getStore());
        history.push("/accountList");
    }

    const gitbook = (
        <IconButton
            className={classes.signOutButton}
            color="inherit"
            onClick={() => window.open(config.doc_path, "_blank")}
        >
            <MenuBookIcon/>
            <Hidden xsDown>
                <Typography
                    variant="caption"
                    style={{color: "#FFFFFF", marginLeft: 5, marginTop: 3}}
                >系统文档</Typography>
            </Hidden>
        </IconButton>
    );

    const gitlab = (
        <IconButton
            className={classes.signOutButton}
            color="inherit"
            onClick={() => window.open(config.main_site, "_blank")}
        >
            <GitHubIcon/>
            <Hidden xsDown>
                <Typography
                    variant="caption"
                    style={{color: "#FFFFFF", marginLeft: 5, marginTop: 3}}
                >GitLab</Typography>
            </Hidden>
        </IconButton>
    );

    const logoutBtn = (
        <IconButton
            className={classes.signOutButton}
            color="inherit"
            onClick={onLogout}
        >
            <InputIcon/>
            <Hidden xsDown>
                <Typography
                    variant="caption"
                    style={{color: "#FFFFFF", marginLeft: 5, marginTop: 3}}
                >安全退出</Typography>
            </Hidden>
        </IconButton>
    );

    const logoutMockBtn = (
        <IconButton
            className={classes.signOutButton}
            color="inherit"
            onClick={onMockOut}
        >
            <EmojiNatureIcon/>
            <Typography
                variant="caption"
                style={{color: "#FFFFFF", marginLeft: 5}}
            >退出 {user.userName}</Typography>
        </IconButton>
    );

    return (
        <AppBar
            {...rest}
            className={clsx(classes.root, className)}
        >
            <Toolbar>
                <RouterLink to="/">
                    <img
                        alt="Logo"
                        style={{height: 60}}
                        src="/images/logos/logo.svg"
                    />
                </RouterLink>
                <Hidden xsDown>
                    <Notice/>
                </Hidden>
                <div className={classes.flexGrow}/>
                <Hidden lgUp>
                    <IconButton
                        color="inherit"
                        onClick={onSidebarOpen}
                    >
                        <MenuIcon/>
                    </IconButton>
                </Hidden>
                {user.mock ? logoutMockBtn : (
                    <>
                        {gitlab}
                        {gitbook}
                        {logoutBtn}
                    </>
                )}
            </Toolbar>
        </AppBar>
    );
};

Topbar.propTypes = {
    className: PropTypes.string,
    onSidebarOpen: PropTypes.func
};

export default Topbar;
