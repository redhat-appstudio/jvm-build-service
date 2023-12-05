import * as React from 'react';
import {useEffect, useState} from 'react';
import {ArtifactSummaryDTO, ArtifactSummaryResourceService} from "../../services/openapi";
import {ChartDonut} from '@patternfly/react-charts';
type ArtifactStatusParamsType = {
  label: string,
};
const ArtifactSummaryPieChart: React.FunctionComponent<ArtifactStatusParamsType> = (params): JSX.Element => {
    const initial: ArtifactSummaryDTO = {missing:0, failed:0,built:0,total:0}
    const [status, setStatus] = useState(initial);
    const [error, setError] = useState(false);
    const [state, setState] = useState('');

    useEffect(() => {
        setState('loading');
        ArtifactSummaryResourceService.getApiArtifactSummary(params.label).then()
            .then((res) => {
                setState('success');
                setStatus(res);
            })
            .catch((err) => {
                console.error('Error:', err);
                setState('error');
                setError(err);
            });
    }, []);

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
            ariaDesc="Artifact Status"
            ariaTitle="Artifact Status"
            constrainToVisibleArea
            data={[{x: 'Successful', y: status.built / status.total * 100, color: "red"}, {x: 'Failed', y: status.failed / status.total * 100}, {x: 'Missing', y: status.missing / status.total * 100}]}
            legendData={[{name: 'Successful: ' + status.built}, {name: 'Failed: ' + status.failed},  {name: 'Missing: ' + status.missing}]}
            labels={({datum}) => `${datum.x}: ${datum.y}%`}
            legendOrientation="vertical"
            legendPosition="right"
            name="artifactsummary"
            padding={{
                bottom: 20,
                left: 20,
                right: 180, // Adjusted to accommodate legend
                top: 20
            }}
            title="Artifacts"
            colorScale={["#38812F", "#A30000", "#06C", "#EC7A08"]}
            width={350}
        />
    </div>
};

export {ArtifactSummaryPieChart};
