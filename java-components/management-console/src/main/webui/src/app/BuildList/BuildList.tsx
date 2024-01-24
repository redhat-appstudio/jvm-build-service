import * as React from 'react';
import {useEffect, useState} from 'react';
import {
  ActionListItem,
  Bullseye,
  Dropdown,
  DropdownItem,
  DropdownList,
  EmptyState,
  EmptyStateBody,
  EmptyStateHeader,
  EmptyStateIcon,
  Label,
  MenuToggle,
  MenuToggleElement,
  Pagination, SearchInput, Timestamp,
  Toolbar,
  ToolbarContent,
  ToolbarItem,
} from '@patternfly/react-core';
import {Table, Tbody, Td, Th, Thead, Tr} from '@patternfly/react-table';
import SearchIcon from '@patternfly/react-icons/dist/esm/icons/search-icon';
import {BuildHistoryResourceService, BuildListDTO, BuildQueueResourceService} from "../../services/openapi";
import {
  CheckCircleIcon,
  EllipsisVIcon,
  ErrorCircleOIcon,
  IceCreamIcon,
  WarningTriangleIcon
} from "@patternfly/react-icons";
import {Link} from "react-router-dom";


const columnNames = {
  name: 'Build ID',
  repo: 'Repo',
  tag: 'Tag',
  creationTime: 'Creation Time',
  actions: 'Actions',
};

const BuildList: React.FunctionComponent = () => {
  const [builds, setBuilds] = useState(Array<BuildListDTO>);
  const [error, setError] = useState(false);
  const [state, setState] = useState('');

  const [count, setCount] = React.useState(0);
  const [page, setPage] = React.useState(1);
  const [perPage, setPerPage] = React.useState(20);

  const [stateFilter, setStateFilter] = useState('');
  const [gavFilter, setGavFilter] = useState('');
  const [dropDownOpen, setDropDownOpen] = useState(false);

  let transientGav = ''

  useEffect(() => {
    setState('loading');
    BuildHistoryResourceService.getApiBuildsHistory(gavFilter, page, perPage, stateFilter).then()
      .then((res) => {
        console.log(res);
        setState('success');
        setBuilds(res.items);
        setCount(res.count);
      })
      .catch((err) => {
        console.error('Error:', err);
        setState('error');
        setError(err);
      });
  }, [perPage, page, gavFilter, stateFilter]);

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

  const doSearch = (event) => {
    if (event.key === 'Enter') {
      setGavFilter(transientGav)
    }
  }
  const dropDownLabel = (state: string) => {
    switch (state) {
      case '':
        return "All";
      case'complete':
        return <><CheckCircleIcon color="green" />Successful</>
      case 'contaminated':
        return <><WarningTriangleIcon color="orange" />Contaminated</>
      case 'failed':
        return <><ErrorCircleOIcon color="red" />Failed</>
    }
    return state
  }
  const toolbar = (
    <Toolbar id="search-input-filter-toolbar">
      <ToolbarContent>

        <ToolbarItem variant="search-filter"><SearchInput aria-label="Search by GAV" value={gavFilter} onClear={() => setGavFilter('')} onKeyDown={doSearch} onBlur={() => setGavFilter(transientGav)} onChange={(e, v) => {transientGav = v}} /></ToolbarItem>

        <ToolbarItem variant="search-filter">
          <Dropdown
            isOpen={dropDownOpen}
            onOpenChange={(isOpen) => setDropDownOpen(isOpen)}
            onOpenChangeKeys={['Escape']}
            toggle={(toggleRef) => (
              <MenuToggle ref={toggleRef} onClick={() => setDropDownOpen(!dropDownOpen)} isExpanded={dropDownOpen}>
                {dropDownLabel(stateFilter)}
              </MenuToggle>
            )}
            id="context-selector"
            onSelect={(e,v) => {setStateFilter(typeof v === 'string'? v :''); setDropDownOpen(false);}}
            isScrollable
          >
            <DropdownList>
              <DropdownItem itemId={''} key={'allitems'} onSelect={() => setStateFilter('')} >All</DropdownItem>
              <DropdownItem itemId={'complete'} key={'complete'} onSelect={() => setStateFilter('complete')} >
                <Label color="green" icon={<CheckCircleIcon/>}>
                  Successful
                </Label></DropdownItem>
              <DropdownItem itemId={'contaminated'} key={'contaminated'} onSelect={() => setStateFilter('contaminated')} >
                <Label color="orange" icon={<WarningTriangleIcon/>}>
                  Contaminated
                </Label>
              </DropdownItem>
              <DropdownItem itemId={'failed'} key={'failed'} onSelect={() => setStateFilter('failed')} >
                <Label color="red" icon={<ErrorCircleOIcon/>}>
                  Failed
                </Label>
              </DropdownItem>
            </DropdownList>
          </Dropdown>
        </ToolbarItem>
        <ToolbarItem variant="pagination">{toolbarPagination}</ToolbarItem>
      </ToolbarContent>
    </Toolbar>
  );

  const emptyState = (
    <EmptyState>
      <EmptyStateHeader headingLevel="h4" titleText="No results found" icon={<EmptyStateIcon icon={SearchIcon} />} />
      <EmptyStateBody>No results match the criteria.</EmptyStateBody>
    </EmptyState>
  );


  return (
    <React.Fragment>
      {toolbar}
      <Table aria-label="Build List">
        <Thead>
          <Tr>
            <Th width={10}>Status</Th>
            <Th width={10}>{columnNames.name}</Th>
            <Th width={20}>{columnNames.repo}</Th>
            <Th width={10}>{columnNames.tag}</Th>
            <Th width={10}>{columnNames.creationTime}</Th>
            <Th width={10}>{columnNames.actions}</Th>
          </Tr>
        </Thead>
        <Tbody>
          {builds.length > 0 &&
            builds.map((build: BuildListDTO, index) => (
                  <BuildRow build={build} key={index}></BuildRow>
            ))}
          {builds.length === 0 && (
            <Tr>
              <Td colSpan={8}>
                <Bullseye>{emptyState}</Bullseye>
              </Td>
            </Tr>
          )}
        </Tbody>
      </Table>
    </React.Fragment>
  );
};

type BuildActionsType = {
  build: BuildListDTO,
};

const BuildRow: React.FunctionComponent<BuildActionsType> = (initialBuild):JSX.Element => {

  const [build, setBuild] = useState(initialBuild.build);

  const [isOpen, setIsOpen] = React.useState(false);
  const onToggle = () => {
    setIsOpen(!isOpen);
  };

  const onSelect = (event: React.MouseEvent<Element, MouseEvent> | undefined) => {
    event?.stopPropagation();
    setIsOpen(!isOpen);
  };

  const rebuild = (event: React.SyntheticEvent<HTMLLIElement>) => {
    BuildQueueResourceService.postApiBuildsQueue(build.name)
      .then(() => {
        const copy = Object.assign({}, build);
        copy.inQueue = true
        setBuild(copy)
      })
  };

  const dropdownItems = (
    <>
      <DropdownItem key="rebuild" onSelect={rebuild} onClick={rebuild}>
        Rebuild
      </DropdownItem>
    </>
  );

  const icon= function (build: BuildListDTO) {
    if (build.inQueue) {
      return <>
        {statusIcon(build)}
        <Label color="blue" icon={<IceCreamIcon />}>
          In Build Queue
        </Label>
      </>
    }
    return statusIcon(build)
  }
  const statusIcon= function (build: BuildListDTO) {
    if (build.contaminated) {
      return <Label color="orange" icon={<CheckCircleIcon />}>
        Build Contaminated
      </Label>
    } else if (build.succeeded) {
      return <Label color="green" icon={<CheckCircleIcon />}>
        Build Successful
      </Label>
    }
    return <Label color="red" icon={<ErrorCircleOIcon />}>
      Build Failed
    </Label>
  }

  return <Tr key={build.name}>
    <Td>
      {icon(build)}
    </Td>
    <Td dataLabel={columnNames.name} modifier="truncate">
      <Link to={`/builds/build/${build.id}`}>{build.name}</Link>
    </Td>
    <Td dataLabel={columnNames.repo} modifier="truncate">
      {build.scmRepo}
    </Td>
    <Td dataLabel={columnNames.tag} modifier="truncate">
      {build.tag}
    </Td>
    <Td dataLabel={columnNames.creationTime} modifier="truncate">
      <Timestamp date={new Date(build.creationTime)}></Timestamp>
    </Td>
    <Td>
      <ActionListItem>
        <Dropdown
          onSelect={onSelect}
          toggle={(toggleRef: React.Ref<MenuToggleElement>) => (
            <MenuToggle
              ref={toggleRef}
              onClick={onToggle}
              variant="plain"
              isExpanded={isOpen}
              aria-label="Action list single group kebab"
            >
              <EllipsisVIcon />
            </MenuToggle>
          )}
          isOpen={isOpen}
          onOpenChange={(isOpen: boolean) => setIsOpen(isOpen)}
        >
          <DropdownList>{dropdownItems}</DropdownList>
        </Dropdown>
      </ActionListItem>
    </Td>
  </Tr>
}
export { BuildList };
