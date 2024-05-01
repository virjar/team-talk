import React, {useContext} from "react";
import clsx from "clsx";
import PropTypes from "prop-types";
import {AccountBox, Home, SettingsApplications, ShowChart} from "@mui/icons-material"
import {AppContext} from "adapter";
import {Divider, Drawer} from "@mui/material";

import {Profile, SidebarNav} from "./components";
import {createUseStyles} from "react-jss";

const useStyles = createUseStyles(theme => ({
    drawer: {
        width: 240,
        [theme.breakpoints.up("lg")]: {
            marginTop: 64,
            height: "calc(100% - 64px)"
        }
    },
    root: {
        backgroundColor: theme.palette.white,
        display: "flex",
        flexDirection: "column",
        height: "100%",
        padding: theme.spacing(2)
    },
    divider: {
        margin: theme.spacing(2, 0)
    },
    nav: {
        marginBottom: theme.spacing(2)
    }
}));

const Sidebar = props => {
    const {user} = useContext(AppContext);
    const {open, variant, onClose, className, ...rest} = props;

    const classes = useStyles();

    let pages = [
        {
            title: "我的",
            href: "/mine",
            icon: <Home/>
        },

    ];

    if (user.isAdmin) {
        pages[pages.length - 1].divider = true;
        pages = pages.concat([
            {
                title: "监控指标",
                href: "/metrics",
                icon: <ShowChart/>
            },
            {
                title: "账号列表",
                href: "/accountList",
                icon: <AccountBox/>
            }, {
                title: "系统面板",
                href: "/systemSettings",
                icon: <SettingsApplications/>
            }]);
    }

    return (
        <Drawer
            anchor="left"
            classes={{paper: classes.drawer}}
            onClose={onClose}
            open={open}
            variant={variant}
        >
            <div
                {...rest}
                className={clsx(classes.root, className)}
            >
                <Profile/>
                <Divider className={classes.divider}/>
                <SidebarNav
                    className={classes.nav}
                    pages={pages}
                />
            </div>
        </Drawer>
    );
};

Sidebar.propTypes = {
    className: PropTypes.string,
    onClose: PropTypes.func,
    open: PropTypes.bool.isRequired,
    variant: PropTypes.string.isRequired
};

export default Sidebar;
