import React, {useEffect, useState} from "react";
import moment from "moment";
import apis from "apis";
import {createUseStyles} from "react-jss";

const useStyles = createUseStyles(() => ({
    flexGrow: {
        flexGrow: 1
    },
    notice: {
        position: "absolute",
        top: "32px",
        transform: "translateY(-50%)",
        left: 0,
        right: 0,
        margin: "0 auto",
        fontSize: 12,
        textAlign: "center"
    }
}));

const Notice = () => {

    const classes = useStyles();

    const [intPushMsg, setIntPushMsg] = useState("");
    const [certificate, setCertificate] = useState({});

    useEffect(() => {
        apis.getIntPushMsg().then(res => {
            setIntPushMsg(res);
        }).catch(() => {
        });
        apis.getNowCertificate().then(res => {
            setCertificate(res);
        }).catch(() => {
        });
    }, []);

    const noticeBtn = (
        <div className={classes.notice}>
            {intPushMsg ? (<div>{intPushMsg}</div>) : null}
            {
                certificate.expire ?
                    (<div>
                        过期时间：<strong>{moment(new Date(Number(certificate.expire))).format("YYYY-MM-DD HH:mm")}</strong>、
                        {certificate.user === "0" ? (<strong>试用版本</strong>) :
                            (<p>授权人：<strong>{certificate.user}</strong></p>)
                        }

                    </div>) : <></>
            }

        </div>
    );

    return (
        <div className={classes.flexGrow}>{noticeBtn}</div>
    );
};

export default Notice;
