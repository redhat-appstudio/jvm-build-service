import * as React from 'react';
import {useEffect, useState} from 'react';
import {
  DataList,
  DataListCell,
  DataListContent,
  DataListItem,
  DataListItemCells,
  DataListItemRow,
  DataListToggle,
  Label,
  Title,
} from '@patternfly/react-core';
import {DeploymentDTO, DeploymentResourceService, IdentifiedDependencyDTO} from "../../services/openapi";
import {EmptyTable} from '@app/EmptyTable/EmptyTable';
import {
  AttentionBellIcon,ContainerNodeIcon,
  IceCreamIcon,
  OkIcon, OutlinedAngryIcon, RedhatIcon, WarningTriangleIcon
} from "@patternfly/react-icons";
import {Link} from "react-router-dom";
import {DependencySet} from "@app/DependencySet/DependencySet";

const DeploymentList: React.FunctionComponent = () => {
  const [deployments, setDeployments] = useState(Array<DeploymentDTO>);
  const [error, setError] = useState(false);
  const [state, setState] = useState('');


  useEffect(() => {
    setState('loading');
    DeploymentResourceService.getApiDeployment().then()
      .then((res) => {
        console.log(res);
        setState('success');
        setDeployments(res);
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

  return (
    <React.Fragment>

      <DataList aria-label="Information">
      {deployments.map((deployment : DeploymentDTO, index) => (
          <DeploymentRow key={index} deployment={deployment}></DeploymentRow>
        ))}
        {deployments.length === 0 && <EmptyTable></EmptyTable>}
      </DataList>
    </React.Fragment>
  );
};

type DeploymentActionsType = {
  deployment: DeploymentDTO,
};

const DeploymentRow: React.FunctionComponent<DeploymentActionsType> = (initialBuild): JSX.Element => {

  const [imagesExpanded, setImagesExpanded] = React.useState(false);

  const toggleImages = () => {
    setImagesExpanded(!imagesExpanded);
  };

  return <DataListItem aria-labelledby="ex-item1" isExpanded={imagesExpanded}>
    <DataListItemRow>
      <DataListToggle
        onClick={() => toggleImages()}
        isExpanded={imagesExpanded}
        id="toggle"
        aria-controls="ex-expand"
      />
      <DataListItemCells
        dataListCells={[
          <DataListCell isIcon key="icon">
            <ContainerNodeIcon/>
          </DataListCell>,
          <DataListCell key="primary content">
            <div id="ex-item1">{initialBuild.deployment.namespace}/{initialBuild.deployment.name}</div>
          </DataListCell>
        ]}
      />
    </DataListItemRow>
    <DataListContent
      aria-label="First expandable content details"
      id="ex-expand1"
      isHidden={!imagesExpanded}
    >
      {initialBuild.deployment.images.map((s) => (
        <><Title headingLevel={"h2"}>Image: {s.fullName}</Title>
          <DependencySet dependencySetId={s.dependencySet}></DependencySet>
        </>))}
    </DataListContent>
  </DataListItem>

}
export {DeploymentList};
