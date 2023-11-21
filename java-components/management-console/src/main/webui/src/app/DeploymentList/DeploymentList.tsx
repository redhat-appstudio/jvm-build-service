import * as React from 'react';
import {useEffect, useState} from 'react';
import {
  DataList,
  DataListAction,
  DataListCell,
  DataListContent,
  DataListItem,
  DataListItemCells,
  DataListItemRow,
  DataListToggle,
  Dropdown,
  DropdownItem,
  DropdownList,
  EmptyState,
  EmptyStateBody,
  EmptyStateHeader,
  EmptyStateIcon,
  Label,
  MenuToggle,
  MenuToggleElement, Progress, ProgressVariant, Title,
} from '@patternfly/react-core';
import SearchIcon from '@patternfly/react-icons/dist/esm/icons/search-icon';
import {BuildListDTO, Dependency, DeploymentDTO, DeploymentResourceService} from "../../services/openapi";
import {
  AttentionBellIcon,
  CheckCircleIcon,
  CodeBranchIcon, ContainerNodeIcon,
  EllipsisVIcon,
  ErrorCircleOIcon, ExclamationIcon,
  IceCreamIcon,
  InProgressIcon, LineIcon, ListIcon, OkIcon, OutlinedAngryIcon, RedhatIcon, StickyNoteIcon, WarningTriangleIcon
} from "@patternfly/react-icons";
import {ChartBullet, ChartDonut} from "@patternfly/react-charts";
import {Link} from "react-router-dom";


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

  const emptyState = (
    <EmptyState>
      <EmptyStateHeader headingLevel="h4" titleText="No results found" icon={<EmptyStateIcon icon={SearchIcon}/>}/>
      <EmptyStateBody>No results match the criteria.</EmptyStateBody>
    </EmptyState>
  );


  return (
    <React.Fragment>

      <DataList aria-label="Information">
      {deployments.map((build, index) => (
          <BuildRow deployment={build}></BuildRow>
        ))}
      </DataList>
    </React.Fragment>
  );
};

type DeploymentActionsType = {
  deployment: DeploymentDTO,
};

const BuildRow: React.FunctionComponent<DeploymentActionsType> = (initialBuild): JSX.Element => {

  const [deployment, setDeployment] = useState(initialBuild.deployment);

  const [imagesExpanded, setImagesExpanded] = React.useState(false);
  const [actionsExpanded, setActionsExpanded] = React.useState(false);
  const toggleActions = () => {
    setActionsExpanded(!actionsExpanded);
  };
  const toggleImages = () => {
    setImagesExpanded(!imagesExpanded);
  };
  const onActionsClick = (event: React.MouseEvent<Element, MouseEvent> | undefined) => {
    event?.stopPropagation();
    setActionsExpanded(!actionsExpanded);
  };

  const health = function (deployment: DeploymentDTO) {
    if (!deployment.analysisComplete) {
      return <Label color="blue" icon={<InProgressIcon />}>
        Image Analysis in Progress
      </Label>
    }
    let untrusted = 0
    let total = 0
    let available = 0
    deployment.images.map((i) => {total += i.totalDependencies; untrusted += i.untrustedDependencies; available += i.availableBuilds})
    let trusted = total - untrusted
    if (total == 0) {
      return <Label color="blue" icon={<StickyNoteIcon />}>
        No Java
      </Label>
    }
    return <>
      {untrusted > 0 && <Label color="red" icon={<WarningTriangleIcon />}>{untrusted} Untrusted Dependencies</Label>}
      {trusted > 0 && <Label color="green" icon={<OkIcon />}>{trusted} Rebuilt Dependencies</Label>}
      {available > 0 && <Label color="orange" icon={<ListIcon />}>{available} Available Rebuilt Dependencies</Label>}

    </>
  }

  const dependencyRow = function (dep : Dependency) {

    return <DataListItem>
      <DataListItemRow>
        <DataListItemCells
          dataListCells={[
            <DataListCell isIcon key="icon">
              {dep.source === 'rebuilt' && <OkIcon color={"green"}></OkIcon>}
              {dep.source === 'redhat' && <RedhatIcon color={"red"}></RedhatIcon>}
              {(dep.source !== 'redhat' && dep.source != 'rebuilt') && <WarningTriangleIcon color={"orange"}></WarningTriangleIcon>}
            </DataListCell>,
            <DataListCell key="primary content">
              {dep.build != undefined && <Link to={`/builds/build/${dep.build}`}>{dep.gav}</Link>}
              {dep.build == undefined && <div id="gav">{dep.gav}</div>}
            </DataListCell>,
            <DataListCell key="primary content">
              {dep.inQueue && <Label color="blue" icon={<IceCreamIcon />}> In Build Queue</Label>}
              {(dep.source !== 'redhat' && dep.source != 'rebuilt' && dep.buildSuccess) && <Label color="orange" icon={<AttentionBellIcon />}>Rebuilt Artifact Available, Image Rebuild Required</Label>}
              {(dep.source !== 'redhat' && dep.source != 'rebuilt' && !dep.buildSuccess && dep.build != undefined) && <Label color="red" icon={<OutlinedAngryIcon />}>Rebuild Failed</Label>}

            </DataListCell>,
          ]}
        />
      </DataListItemRow>
    </DataListItem>
  }

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
            <div id="ex-item1">{deployment.namespace}/{deployment.name}</div>
          </DataListCell>,
          <DataListCell key="health">
            {health(deployment)}
          </DataListCell>
        ]}
      />
    </DataListItemRow>
    <DataListContent
      aria-label="First expandable content details"
      id="ex-expand1"
      isHidden={!imagesExpanded}
    >
      {deployment.images.map((s) => (
        <><Title headingLevel={"h2"}>Image: {s.string}</Title>

        <DataList aria-label="Dependencies">
          {s.dependencies?.map(d => (dependencyRow(d)))}
        </DataList>
        </>

    ))}
    </DataListContent>
  </DataListItem>

}
export {DeploymentList};
