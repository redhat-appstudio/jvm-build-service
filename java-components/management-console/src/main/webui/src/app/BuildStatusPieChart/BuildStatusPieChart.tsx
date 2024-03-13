import * as React from 'react';
import {useEffect, useState} from 'react';
import {BuildSummaryDTO, BuildSummaryResourceService} from "../../services/openapi";
import {ChartDonut} from '@patternfly/react-charts';

type BuildStatusParamsType = {
  label: string,
};
const BuildStatusPieChart: React.FunctionComponent<BuildStatusParamsType> = (params): JSX.Element => {
    const initial: BuildSummaryDTO = {contaminatedBuilds: 0, totalBuilds: 0, runningBuilds: 0, failingBuilds: 0, successfulBuilds: 0}
    const [status, setStatus] = useState(initial);
    const [error, setError] = useState(false);
    const [state, setState] = useState('');

    useEffect(() => {
        setState('loading');
        BuildSummaryResourceService.getApiBuildsStatus(params.label).then()
            .then((res) => {
                setState('success');
                setStatus(res);
            })
            .catch((err) => {
                console.error('Error:', err);
                setState('error');
                setError(err);
            });
    }, [params.label]);

    if (state === 'error')
        return (
            <h1>
                {error.toString()}
            </h1>
        );
    if (state === 'loading')
        return (
            <h1>Loading...</h1>
        )
    return <div style={{height: '230px', width: '350px'}}>
        <ChartDonut
            ariaDesc="Build Status"
            ariaTitle="Build Status"
            constrainToVisibleArea
            data={[{x: 'Successful', y: status.successfulBuilds / status.totalBuilds * 100, color: "red"}, {x: 'Failed', y: status.failingBuilds / status.totalBuilds * 100}, {x: 'Running', y: status.runningBuilds / status.totalBuilds * 100}, {x: 'Contaminated', y: status.contaminatedBuilds / status.totalBuilds * 100}]}
            legendData={[{name: 'Successful: ' + status.successfulBuilds}, {name: 'Failed: ' + status.failingBuilds}, {name: 'Running: ' + status.runningBuilds}, {name: 'Contaminated: ' + status.contaminatedBuilds}]}
            labels={({datum}) => `${datum.x}: ${datum.y}%`}
            legendOrientation="vertical"
            legendPosition="right"
            name="buildstatus"
            padding={{
                bottom: 20,
                left: 20,
                right: 180, // Adjusted to accommodate legend
                top: 20
            }}
            title="Builds"
            colorScale={["#38812F", "#A30000", "#06C", "#EC7A08"]}
            width={350}
        />
    </div>
};

export {BuildStatusPieChart};
