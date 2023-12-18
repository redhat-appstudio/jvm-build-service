import * as React from 'react';
import {useEffect, useState} from 'react';
import {
  ActionListItem, Button,
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
  Pagination, SearchInput,
  Toolbar,
  ToolbarContent,
  ToolbarItem,
} from '@patternfly/react-core';
import {Table, Tbody, Td, Th, Thead, Tr} from '@patternfly/react-table';
import SearchIcon from '@patternfly/react-icons/dist/esm/icons/search-icon';
import {ArtifactEditResourceService, ArtifactHistoryResourceService, ArtifactListDTO} from "../../services/openapi";
import {CheckCircleIcon, EllipsisVIcon, ErrorCircleOIcon, WarningTriangleIcon} from "@patternfly/react-icons";
import {ArtifactEditModal} from "@app/ArtifactEditModal/ArtifactEditModal";
import {EmptyTable} from "@app/EmptyTable/EmptyTable";


const columnNames = {
  status: 'Status',
  gav: 'GAV',
  message: 'Message',
  actions: 'Actions',
};

const ArtifactList: React.FunctionComponent = () => {
  const [builds, setBuilds] = useState(Array<ArtifactListDTO>);
  const [error, setError] = useState(false);
  const [state, setState] = useState('');

  const [stateFilter, setStateFilter] = useState('');
  const [gavFilter, setGavFilter] = useState('');
  const [dropDownOpen, setDropDownOpen] = useState(false);

  const [count, setCount] = React.useState(0);
  const [page, setPage] = React.useState(1);
  const [perPage, setPerPage] = React.useState(20);
  const emptyArtifact: ArtifactListDTO = {gav: ""}
  const [artifact, setArtifact] = useState(emptyArtifact);
  const [modalOpen, setModalOpen] = useState(false);

  let transientGav = ''

  useEffect(() => {
    setState('loading');
    ArtifactHistoryResourceService.getApiArtifactsHistory(gavFilter, page, perPage, stateFilter).then()
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

  const editArtifact = (artifact: ArtifactListDTO) => {
    setArtifact(artifact)
    setModalOpen(true)
  }

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
      titles={{paginationAriaLabel: 'Search filter pagination'}}
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
          return <><CheckCircleIcon color="green"/>Successful</>
      case 'missing':
          return <><WarningTriangleIcon color="orange"/>Missing</>
      case 'failed':
          return <><ErrorCircleOIcon color="red"/>Failed</>
    }
    return state
  }

  const toolbar = (
    <Toolbar id="search-input-filter-toolbar">
      <ToolbarContent>
        <ToolbarItem variant="search-filter"><SearchInput aria-label="Search by GAV" value={gavFilter} onKeyDown={doSearch} onBlur={() => setGavFilter(transientGav)} onChange={(e, v) => {transientGav = v}} /></ToolbarItem>

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
              <DropdownItem itemId={'missing'} key={'missing'} onSelect={() => setStateFilter('missing')} >
                <Label color="orange" icon={<WarningTriangleIcon/>}>
                  Missing
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


  return (
    <React.Fragment>
      {toolbar}
      <ArtifactEditModal artifact={artifact} open={modalOpen} setOpen={setModalOpen}></ArtifactEditModal>
      <Table aria-label="Artifact List">
        <Thead>
          <Tr>
            <Th width={10}>{columnNames.status}</Th>
            <Th width={10}>{columnNames.gav}</Th>
            <Th width={10}>{columnNames.message}</Th>
            <Th width={10}>{columnNames.actions}</Th>
          </Tr>
        </Thead>
        <Tbody>
          {builds.length > 0 &&
            builds.sort((a, b) => a.gav.localeCompare(b.gav)).map((value: ArtifactListDTO, index) => (
              <ArtifactRow key={index} artifact={value} selectArtifact={editArtifact}></ArtifactRow>
            ))}
          {builds.length === 0 && (
            <EmptyTable></EmptyTable>
          )}
        </Tbody>
      </Table>
    </React.Fragment>
  );
};

type BuildActionsType = {
  artifact: ArtifactListDTO,
  selectArtifact: (artifact: ArtifactListDTO) => void

};

const ArtifactRow: React.FunctionComponent<BuildActionsType> = (artifact): JSX.Element => {

  const [isOpen, setIsOpen] = React.useState(false);
  const onToggle = () => {
    setIsOpen(!isOpen);
  };

  const onSelect = (event: React.MouseEvent<Element, MouseEvent> | undefined) => {
    event?.stopPropagation();
    setIsOpen(!isOpen);
  };

  const edit = (event: React.SyntheticEvent<HTMLLIElement>) => {
    artifact.selectArtifact(artifact.artifact)
  };
  const rebuild = (event: React.SyntheticEvent<HTMLLIElement>) => {
    ArtifactEditResourceService.postApiArtifactsEditRebuild(artifact.artifact.gav)
  };


  const dropdownItems = (
    <>
      <DropdownItem key="edit" onSelect={edit} onClick={edit}>
        Edit
      </DropdownItem>
      <DropdownItem key="rebuild" onSelect={rebuild} onClick={rebuild}>
        Rebuild
      </DropdownItem>
    </>
  );
  const statusIcon = function (build: ArtifactListDTO) {
    if (build.succeeded) {
      return <Label color="green" icon={<CheckCircleIcon/>}>
        Artifact Successful
      </Label>
    } else if (build.missing) {
      return <Label color="orange" icon={<WarningTriangleIcon/>}>
        Artifact Missing
      </Label>
    }
    return <Label color="red" icon={<ErrorCircleOIcon/>}>
      Artifact Failed
    </Label>
  }

  return <Tr key={artifact.artifact.gav}>
    <Td>
      {statusIcon(artifact.artifact)}
    </Td>
    <Td dataLabel={columnNames.gav} modifier="truncate">
      {artifact.artifact.gav}
    </Td>
    <Td dataLabel={columnNames.message} modifier="truncate">
      {artifact.artifact.message}
    </Td>
    <Td dataLabel={columnNames.actions}>
      <ActionListItem>
        <Dropdown
          onSelect={onSelect}
          toggle={(toggleRef: React.Ref<MenuToggleElement>) => (
            <MenuToggle
              ref={toggleRef}
              onClick={onToggle}
              variant="plain"
              isExpanded={isOpen}
              aria-label="Artifact Actions"
            >
              <EllipsisVIcon/>
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
export {ArtifactList};
