import * as React from 'react';
import {BuildStatusPieChart} from "@app/BuildStatusPieChart/BuildStatusPieChart";
import {ArtifactSummaryPieChart} from "@app/ArtifactSummaryPieChart/ArtifactSummaryPieChart";
import {Card, CardBody, CardHeader, Flex, FlexItem, Grid, GridItem} from "@patternfly/react-core";

const Dashboard: React.FunctionComponent = () => {
    return <Flex >
        <FlexItem>
            <Card >
                <CardBody>
                    <BuildStatusPieChart></BuildStatusPieChart>
                </CardBody>
            </Card>
        </FlexItem>
        <FlexItem>
            <Card>
                <CardBody>
                    <ArtifactSummaryPieChart></ArtifactSummaryPieChart>
                </CardBody>
            </Card>
        </FlexItem>
    </Flex>
}

export {Dashboard};
