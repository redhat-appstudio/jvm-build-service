import * as React from 'react';
import {useEffect, useState} from 'react';
import {
  Dropdown,
  DropdownItem,
  DropdownList,
  Label,
  MenuToggle,
  Pagination, SearchInput,
  Toolbar,
  ToolbarContent,
  ToolbarItem,
} from '@patternfly/react-core';
import {ArtifactHistoryResourceService, ArtifactListDTO} from "../../services/openapi";
import {CheckCircleIcon, ErrorCircleOIcon, WarningTriangleIcon} from "@patternfly/react-icons";
import {StoredArtifactView} from "../../components";

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
        return "State";
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
      <StoredArtifactView artifacts={builds}></StoredArtifactView>

    </React.Fragment>
  );
};

export {ArtifactList};
