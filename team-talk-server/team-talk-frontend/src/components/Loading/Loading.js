/* eslint-disable */
import React from 'react';

const Loading = () => {

    return (
        <div style={{textAlign: 'center'}}>
            <svg xmlns="http://www.w3.org/2000/svg" xlink="http://www.w3.org/1999/xlink" width="100px" height="100px"
                 viewBox="0 0 100 100" preserveAspectRatio="xMidYMid">
                <circle cx="50" cy="50" r="32" strokeWidth="9" stroke="#d2d2cb"
                        strokeDasharray="50.26548245743669 50.26548245743669" fill="none" strokeLinecap="round"
                        transform="rotate(287.76 50 50)">
                    <animateTransform attributeName="transform" type="rotate" dur="1s" repeatCount="indefinite"
                                      keyTimes="0;1" values="0 50 50;360 50 50"></animateTransform>
                </circle>
                <circle cx="50" cy="50" r="22" strokeWidth="9" stroke="#4d695d"
                        strokeDasharray="34.55751918948772 34.55751918948772" strokeDashoffset="34.55751918948772"
                        fill="none" strokeLinecap="round" transform="rotate(-287.76 50 50)">
                    <animateTransform attributeName="transform" type="rotate" dur="1s" repeatCount="indefinite"
                                      keyTimes="0;1" values="0 50 50;-360 50 50"></animateTransform>
                </circle>
            </svg>
        </div>
    );

};

export default Loading;
