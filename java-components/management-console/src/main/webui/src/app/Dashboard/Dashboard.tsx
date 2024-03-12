import * as React from 'react';
import {BuildStatusPieChart} from "@app/BuildStatusPieChart/BuildStatusPieChart";
import {ArtifactSummaryPieChart} from "@app/ArtifactSummaryPieChart/ArtifactSummaryPieChart";

import {
  Card,
  CardBody,
  CardHeader,
  Flex,
  FlexItem,
} from "@patternfly/react-core";
import {useState} from "react";
import {LabelSelector} from "@app/LabelSelector/LabelSelector";


const Dashboard: React.FunctionComponent = () => {
  const [label, setLabel] = useState('');

    return <Card>
    <CardBody>
      <CardHeader>
        <LabelSelector labelSelected={setLabel} ></LabelSelector>
      </CardHeader>
      <Flex >
        <FlexItem>
          <Card >
            <CardBody>
              <BuildStatusPieChart label={label}></BuildStatusPieChart>
            </CardBody>
          </Card>
        </FlexItem>
        <FlexItem>
          <Card>
            <CardBody>
              <ArtifactSummaryPieChart label={label}></ArtifactSummaryPieChart>
            </CardBody>
          </Card>
        </FlexItem>
      </Flex>
    </CardBody>
  </Card>

}

export {Dashboard};
