import * as React from 'react';
import {
  Toolbar,
  ToolbarContent,
  ToolbarItem,
  Pagination,
  EmptyState,
  EmptyStateHeader,
  EmptyStateBody,
  Bullseye,
  EmptyStateIcon, ActionListItem, Dropdown,
} from '@patternfly/react-core';
import { Table, Thead, Tr, Th, Tbody, Td } from '@patternfly/react-table';
import SearchIcon from '@patternfly/react-icons/dist/esm/icons/search-icon';
import { Label } from '@patternfly/react-core';
import {BuildQueueListDTO, BuildQueueResourceService} from "../../services/openapi";
import {useEffect, useState} from "react";
import {CheckCircleIcon, ErrorCircleOIcon, ExclamationIcon} from "@patternfly/react-icons";


const columnNames = {
  priority: 'Priority',
  artifact: 'Artifact',
};
const BuildQueueList: React.FunctionComponent = () => {
  const [builds, setBuilds] = useState(Array<BuildQueueListDTO>);
  const [error, setError] = useState(false);
  const [state, setState] = useState('');

  const [count, setCount] = React.useState(0);
  const [page, setPage] = React.useState(1);
  const [perPage, setPerPage] = React.useState(20);

  useEffect(() => {
    setState('loading');
    BuildQueueResourceService.getApiBuildsQueue(page, perPage).then()
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
  }, [perPage, page]);

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

  const emptyState = (
    <EmptyState>
      <EmptyStateHeader headingLevel="h4" titleText="No results found" icon={<EmptyStateIcon icon={SearchIcon} />} />
      <EmptyStateBody>No results match the criteria.</EmptyStateBody>
    </EmptyState>
  );

  const icon= function (build: BuildQueueListDTO) {
    if (build.priority) {
      return <Label color="orange" icon={<ExclamationIcon />}>
        Priority
      </Label>
    }
    return <></>
  }

  return (
    <React.Fragment>
      {toolbar}
      <Table aria-label="Build List">
        <Thead>
          <Tr>
            <Th width={10}>{columnNames.priority}</Th>
            <Th width={90}>{columnNames.artifact}</Th>
          </Tr>
        </Thead>
        <Tbody>
          {builds.length > 0 &&
            builds.map(build => (
              <Tr key={build.artifact}>
                <Td dataLabel={columnNames.priority} >
                  {icon(build)}
                </Td>
                <Td dataLabel={columnNames.artifact} modifier="truncate">
                  {build.artifact}
                </Td>
              </Tr>
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

export { BuildQueueList };
