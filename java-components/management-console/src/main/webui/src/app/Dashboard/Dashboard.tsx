import * as React from 'react';
import {BuildStatusPieChart} from "@app/BuildStatusPieChart/BuildStatusPieChart";
import {ArtifactSummaryPieChart} from "@app/ArtifactSummaryPieChart/ArtifactSummaryPieChart";

import {
  Card,
  CardBody,
  CardHeader, Dropdown,
  DropdownItem,
  DropdownList,
  Flex,
  FlexItem, MenuToggle,
} from "@patternfly/react-core";
import {
  ArtifactLabelName,
  ArtifactLabelResourceService,
} from "../../services/openapi";
import {useEffect, useState} from "react";


const Dashboard: React.FunctionComponent = () => {
  const [labels, setLabels] = useState(Array<ArtifactLabelName>);
  const [label, setLabel] = useState('');
  const [error, setError] = useState(false);
  const [state, setState] = useState('');
  const [dropDownOpen, setDropDownOpen] = useState(false);

  useEffect(() => {
    setState('loading');
    ArtifactLabelResourceService.getApiArtifactLabels().then()
      .then((res) => {
        setState('success');
        setLabels(res);
      })
      .catch((err) => {
        console.error('Error:', err);
        setState('error');
        setError(err);
      });
  }, [label]);

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

  const onSelect = (ev: React.MouseEvent<Element, MouseEvent> | undefined, itemId: string | number | undefined) => {
    if (typeof itemId === 'number' || typeof itemId === 'undefined') {
      return;
    }
    setLabel(itemId);
    setDropDownOpen(!dropDownOpen);
  };

    return <Card>
    <CardBody>
      <CardHeader>
        <Dropdown
          isOpen={dropDownOpen}
          onOpenChange={(isOpen) => setDropDownOpen(isOpen)}
          onOpenChangeKeys={['Escape']}
          toggle={(toggleRef) => (
            <MenuToggle ref={toggleRef} onClick={() => setDropDownOpen(!dropDownOpen)} isExpanded={dropDownOpen}>
              {label == '' ? 'All' : label}
            </MenuToggle>
          )}
          id="context-selector"
          onSelect={onSelect}
          isScrollable
        >
        <DropdownList>
          <DropdownItem itemId={''} key={'allitems'} onSelect={() => setLabel('')} >All</DropdownItem>
          {labels.map((item, index) => {
            return (
              <DropdownItem
                itemId={item.name}
                key={index}
                onSelect={() => setLabel(item.name == undefined ? '' : item.name)}
              >
                {item.name}
              </DropdownItem>
            );
          })}
        </DropdownList>
        </Dropdown>
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
