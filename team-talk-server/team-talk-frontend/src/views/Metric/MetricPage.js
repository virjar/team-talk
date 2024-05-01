import React, {useState} from 'react';
import {Card, CardContent, CardHeader, MenuItem, Select} from "@mui/material";
import MetricCharsV2 from "components/MetricCharts";
import PropTypes from "prop-types";
import {createUseStyles} from "react-jss";


const useStyles = createUseStyles(theme => ({
    item: {
        marginTop: theme.spacing(5)
    }
}));


const MetricPage = (props) => {
    const {configs, bottomLegend, ...rest} = props;
    const classes = useStyles();
    const [accuracy, setAccuracy] = useState("hours");
    return (<Card {...rest}>
            <CardHeader
                action={
                    (<Select
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
                    </Select>)
                }
            />
            <CardContent>
                {
                    configs.map((config) =>
                        <MetricCharsV2
                            className={classes.item}
                            key={config.title}
                            title={config.title}
                            accuracy={accuracy}
                            bottomLegend={bottomLegend || config['bottomLegend']}
                            mql={config.mql}/>
                    )
                }

            </CardContent>
        </Card>
    );
}


MetricPage.propTypes = {
    configs: PropTypes.array.isRequired,
    bottomLegend: PropTypes.bool
};

export default MetricPage;