import React, {useState} from 'react';
import PropTypes from 'prop-types';
import PerfectScrollbar from 'react-perfect-scrollbar';
import KeyboardArrowDownIcon from '@mui/icons-material/KeyboardArrowDown';
import KeyboardArrowUpIcon from '@mui/icons-material/KeyboardArrowUp';
import {Box, Checkbox, Collapse, IconButton, Table, TableBody, TableCell, TableHead, TableRow} from '@mui/material';

import Empty from '../Empty';
import Loading from 'components/Loading';

const CollapseRow = (props) => {
    const {row, columns, checkbox, checked, onCheckboxChange, renderCollapse} = props;
    const [open, setOpen] = useState(false);

    return (
        <React.Fragment>
            <TableRow>
                <TableCell>
                    <IconButton aria-label="expand row" size="small" onClick={() => setOpen(!open)}>
                        {open ? <KeyboardArrowUpIcon/> : <KeyboardArrowDownIcon/>}
                    </IconButton>
                </TableCell>
                {checkbox ? (
                    <TableCell padding="checkbox">
                        <Checkbox
                            checked={checked}
                            color="primary"
                            onChange={onCheckboxChange}
                            value="true"
                        />
                    </TableCell>
                ) : null}
                {columns.map(col => (
                    <TableCell
                        key={col.label}>{typeof col.render === 'function' ? col.render(row) : row[col.key]}</TableCell>
                ))}
            </TableRow>
            <TableRow>
                <TableCell style={{paddingBottom: 0, paddingTop: 0}} colSpan={6}>
                    <Collapse in={open} timeout="auto" unmountOnExit>
                        <Box margin={1}>
                            {renderCollapse(row)}
                        </Box>
                    </Collapse>
                </TableCell>
            </TableRow>
        </React.Fragment>
    );
}

const DataTable = props => {
    let {
        data,
        columns,
        size = "medium",
        collapse = false,
        renderCollapse = () => (<></>),
        checkbox = false,
        checkedKey = '',
        checked = [],
        handleSelectAll = () => {
        },
        handleSelectOne = () => {
        },
        style = {},
        loading = false,
    } = props;

    return (
        <PerfectScrollbar style={style}>
            {loading ?
                (<Loading/>)
                :
                <Table size={size}>
                    <TableHead>
                        <TableRow>
                            {collapse ? (
                                <TableCell/>
                            ) : null}
                            {checkbox ? (
                                <TableCell padding="checkbox">
                                    <Checkbox
                                        checked={checked.length === data.length}
                                        color="primary"
                                        indeterminate={
                                            checked.length > 0 &&
                                            checked.length < data.length
                                        }
                                        onChange={handleSelectAll}
                                    />
                                </TableCell>
                            ) : null}
                            {columns.map(item => (
                                <TableCell key={item.label}>{item.label}</TableCell>
                            ))}
                        </TableRow>
                    </TableHead>
                    <TableBody>
                        {data.length > 0 ? (
                            data.map((row, index) => {
                                if (collapse) {
                                    return checkbox ? (<CollapseRow
                                        checkbox={checkbox}
                                        checked={(() => {
                                            return checked.indexOf(row[checkedKey]) !== -1
                                        })()}
                                        onCheckboxChange={event => handleSelectOne(event, row[checkedKey])}
                                        key={String(index)}
                                        row={row}
                                        columns={columns}
                                        renderCollapse={renderCollapse}
                                    />) : (<CollapseRow
                                        key={String(index)}
                                        row={row}
                                        columns={columns}
                                        renderCollapse={renderCollapse}
                                    />)
                                }
                                return (
                                    <TableRow key={String(index)} hover>
                                        {checkbox ? (
                                            <TableCell padding="checkbox">
                                                <Checkbox
                                                    checked={
                                                        (() => {
                                                            return checked.indexOf(row[checkedKey]) !== -1
                                                        })()
                                                    }
                                                    color="primary"
                                                    onChange={event => handleSelectOne(event, row[checkedKey])}
                                                    value="true"
                                                />
                                            </TableCell>
                                        ) : null}
                                        {columns.map(col => (
                                            <TableCell
                                                key={col.label}>{typeof col.render === 'function' ? col.render(row) : row[col.key]}</TableCell>
                                        ))}
                                    </TableRow>
                                )
                            })
                        ) : (
                            <TableRow>
                                <TableCell colSpan={columns.length + (checkbox ? 1 : 0)}>
                                    <Empty text="暂无数据"/>
                                </TableCell>
                            </TableRow>
                        )}
                    </TableBody>
                </Table>
            }
        </PerfectScrollbar>
    );
};

DataTable.propTypes = {
    data: PropTypes.array.isRequired,
    columns: PropTypes.array.isRequired
};

export default DataTable;
