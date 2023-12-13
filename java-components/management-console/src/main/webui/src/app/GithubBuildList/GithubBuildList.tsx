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
  EmptyState,
  EmptyStateBody,
  EmptyStateHeader,
  EmptyStateIcon,
  Label, Pagination, Title, Toolbar, ToolbarContent, ToolbarItem,
} from '@patternfly/react-core';
import SearchIcon from '@patternfly/react-icons/dist/esm/icons/search-icon';
import {
  IdentifiedDependencyDTO,
  DeploymentDTO,
  DeploymentResourceService,
  GithubBuildDTO, GithubBuildsResourceService
} from "../../services/openapi";
import {EmptyTable} from '@app/EmptyTable/EmptyTable';
import {
  AttentionBellIcon,ContainerNodeIcon,
  IceCreamIcon,
  InProgressIcon, ListIcon, OkIcon, OutlinedAngryIcon, RedhatIcon, StickyNoteIcon, WarningTriangleIcon
} from "@patternfly/react-icons";
import {Link} from "react-router-dom";

const GithubBuildList: React.FunctionComponent = () => {
  const [builds, setBuilds] = useState(Array<GithubBuildDTO>);
  const [error, setError] = useState(false);
  const [state, setState] = useState('');

  const [count, setCount] = React.useState(0);
  const [page, setPage] = React.useState(1);
  const [perPage, setPerPage] = React.useState(20);

  useEffect(() => {
    setState('loading');
    GithubBuildsResourceService.getApiBuildsGithub(page, perPage).then()
      .then((res) => {
        console.log(res);
        setState('success');
        setBuilds(res.items);
        setCount(res.count)
      })
      .catch((err) => {
        console.error('Error:', err);
        setState('error');
        setError(err);
      });
  }, [perPage, page])

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

  const onSetPage = (_event: React.MouseEvent | React.KeyboardEvent | MouseEvent, newPage: number) => {
    setPage(newPage);
  };

  const onPerPageSelect = (
    _event: React.MouseEvent | React.KeyboardEvent | MouseEvent,
    newPerPage: number,
    newPage: number
  ) => {
    setPerPage(newPerPage);
    setPage(newPage);
  };
  const toolbarPagination = (
    <Pagination
      titles={{ paginationAriaLabel: 'Search filter pagination' }}
      itemCount={count}
      widgetId="search-input-mock-pagination"
      perPage={perPage}
      page={page}
      onPerPageSelect={onPerPageSelect}
      onSetPage={onSetPage}
      isCompact
    />
  );

  const toolbar = (
    <Toolbar id="search-input-filter-toolbar">
      <ToolbarContent>
        <ToolbarItem variant="pagination">{toolbarPagination}</ToolbarItem>
      </ToolbarContent>
    </Toolbar>
  );
  return (
    <React.Fragment>
      {toolbar}
      <DataList aria-label="Information">
      {builds.map((build, index) => (
          <BuildRow key={index} build={build}></BuildRow>
        ))}
        {builds.length === 0 && <EmptyTable></EmptyTable>}
      </DataList>
    </React.Fragment>
  );
};

type DeploymentActionsType = {
  build: GithubBuildDTO,
};

const BuildRow: React.FunctionComponent<DeploymentActionsType> = (initialBuild): JSX.Element => {

  const [build, setBuild] = useState(initialBuild.build);

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

  const health = function (deployment: GithubBuildDTO) {
    let untrusted = deployment.untrustedDependencies
    let total = deployment.totalDependencies
    let available = deployment.availableBuilds
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
  const dependencyRow = function (dep : IdentifiedDependencyDTO) {

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
            <div id="ex-item1">{build.name}</div>
          </DataListCell>,
          <DataListCell key="health">
            {health(build)}
          </DataListCell>
        ]}
      />
    </DataListItemRow>
    <DataListContent
      aria-label="First expandable content details"
      id="ex-expand1"
      isHidden={!imagesExpanded}
    >
        <><Title headingLevel={"h2"}>Build: {build.name}</Title>

        <DataList aria-label="Dependencies">
          {build.dependencies?.map(d => (dependencyRow(d)))}
        </DataList>
        </>
    </DataListContent>
  </DataListItem>

}
export {GithubBuildList};
