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
  MenuToggleElement, Progress, ProgressVariant,
} from '@patternfly/react-core';
import SearchIcon from '@patternfly/react-icons/dist/esm/icons/search-icon';
import {BuildListDTO, DeploymentDTO, DeploymentResourceService} from "../../services/openapi";
import {
  CheckCircleIcon,
  CodeBranchIcon,
  EllipsisVIcon,
  ErrorCircleOIcon,
  IceCreamIcon,
  InProgressIcon
} from "@patternfly/react-icons";


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
  const onImagesClick = (event: React.MouseEvent<Element, MouseEvent> | undefined) => {
    event?.stopPropagation();
    setImagesExpanded(!imagesExpanded);
  };


  const health = function (deployment: DeploymentDTO) {
    if (!deployment.analysisComplete) {
      return <Label color="blue" icon={<InProgressIcon />}>
        Image Analysis in Progress
      </Label>
    }
    let untrusted = 0
    let total = 0
    deployment.images.map((i) => {total += i.totalDependencies; untrusted += i.untrustedDependencies})
    if (total == 0) {
      return "No Java"
    }
    return <Progress value={untrusted / total}  title="Title" variant={ProgressVariant.danger} />
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
            <CodeBranchIcon/>
          </DataListCell>,
          <DataListCell key="primary content">
            <div id="ex-item1">{deployment.namespace}/{deployment.name}</div>
          </DataListCell>,
          <DataListCell key="health">
            {health(deployment)}
          </DataListCell>
        ]}
      />
      <DataListAction
        aria-labelledby="ex-item1 ex-action1"
        id="ex-action1"
        aria-label="Actions"
        isPlainButtonAction
      >
        <Dropdown
          popperProps={{position: 'right'}}
          onSelect={toggleActions}
          toggle={(toggleRef: React.Ref<MenuToggleElement>) => (
            <MenuToggle
              ref={toggleRef}
              isExpanded={actionsExpanded}
              onClick={onActionsClick}
              variant="plain"
              aria-label="Data list exapndable example kebaby toggle 1"
            >
              <EllipsisVIcon aria-hidden="true"/>
            </MenuToggle>
          )}
          isOpen={actionsExpanded}
          onOpenChange={(isOpen: boolean) => setActionsExpanded(isOpen)}
        >
          <DropdownList>
            <DropdownItem key="action">Action</DropdownItem>
            {/* Prevent default onClick functionality for example
                  purposes */}
            <DropdownItem key="link" to="#" onClick={(event: any) => event.preventDefault()}>
              Link
            </DropdownItem>
            <DropdownItem key="disabled action" isDisabled>
              Disabled Action
            </DropdownItem>
            <DropdownItem key="disabled link" isDisabled to="#" onClick={(event: any) => event.preventDefault()}>
              Disabled Link
            </DropdownItem>
          </DropdownList>
        </Dropdown>
      </DataListAction>
    </DataListItemRow>
    <DataListContent
      aria-label="First expandable content details"
      id="ex-expand1"
      isHidden={!imagesExpanded}
    >
      Foo {deployment.images.length}
      {deployment.images.map((s) => (
        <>{s.string}
          {s.dependencies?.map(d => (<div id="ex-item1">{d.gav}</div>))}
        </>

    ))}
    </DataListContent>
  </DataListItem>

}
export {DeploymentList};
