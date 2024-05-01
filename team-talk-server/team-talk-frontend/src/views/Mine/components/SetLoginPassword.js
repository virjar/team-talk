import React, {useContext, useState} from 'react';
import {Button, CardContent, CardHeader, Divider, Grid, TextField,} from '@mui/material';
import {AppContext} from "adapter";

const SetLoginPassword = () => {
    const {api} = useContext(AppContext);
    const [newPassword, setNewPassword] = useState('');
    const [newSecondPassword, setNewSecondPassword] = useState('');

    const saveNewPassword = () => {
        if (newPassword !== newSecondPassword) {
            api.errorToast("两次输入的密码不一致，请修正。")
            return
        }
        api.updatePassword({
            newPassword: newPassword,
        }).then(res => {
            if (res.status === 0) {
                api.successToast("操作成功");
            }
        })
    }

    return (
        <>
            <CardHeader title="修改密码"/>
            <Divider/>
            <CardContent>
                <Grid container spacing={2}>
                    <Grid item xs={4}>
                        <TextField
                            style={{width: "100%"}}
                            size="small"
                            label="新密码"
                            type="password"
                            variant="outlined"
                            value={newPassword}
                            onChange={(e) => setNewPassword(e.target.value)}/>
                    </Grid>
                    <Grid item xs={4}>
                        <TextField
                            style={{width: "100%"}}
                            size="small"
                            label="再次输入密码"
                            type="password"
                            variant="outlined"
                            value={newSecondPassword}
                            onChange={(e) => setNewSecondPassword(e.target.value)}/>
                    </Grid>
                    <Grid item xs={2}>
                        <Button fullWidth variant="contained" color="primary" onClick={saveNewPassword}>
                            应用
                        </Button>
                    </Grid>
                </Grid>
            </CardContent>
        </>
    );
};

export default SetLoginPassword;