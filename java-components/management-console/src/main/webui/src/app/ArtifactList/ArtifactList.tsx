import * as React from 'react';
import {useEffect, useState} from 'react';
import {
  Bullseye,
  EmptyState,
  EmptyStateBody,
  EmptyStateHeader,
  EmptyStateIcon,
  Label,
  Pagination,
  Toolbar,
  ToolbarContent,
  ToolbarItem,
} from '@patternfly/react-core';
import {Table, Tbody, Td, Th, Thead, Tr} from '@patternfly/react-table';
import SearchIcon from '@patternfly/react-icons/dist/esm/icons/search-icon';
import {ArtifactHistoryResourceService, ArtifactListDTO} from "../../services/openapi";
import {CheckCircleIcon, ErrorCircleOIcon} from "@patternfly/react-icons";


const columnNames = {
  status: 'Status',
  gav: 'GAV',
};

const ArtifactList: React.FunctionComponent = () => {
  const [builds, setBuilds] = useState(Array<ArtifactListDTO>);
  const [error, setError] = useState(false);
  const [state, setState] = useState('');

  const [count, setCount] = React.useState(0);
  const [page, setPage] = React.useState(1);
  const [perPage, setPerPage] = React.useState(20);


  useEffect(() => {
    setState('loading');
    ArtifactHistoryResourceService.getApiArtifactsHistory(page, perPage).then()
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

  return (
    <React.Fragment>
      {toolbar}
      <Table aria-label="Artifact List">
        <Thead>
          <Tr>
            <Th width={10}>{columnNames.status}</Th>
            <Th width={10}>{columnNames.gav}</Th>
          </Tr>
        </Thead>
        <Tbody>
          {builds.length > 0 &&
            builds.sort((a,b) => a.gav.localeCompare(b.gav)).map((value, index) => (
                  <ArtifactRow build={value}></ArtifactRow>
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
  build: ArtifactListDTO,
};

const ArtifactRow: React.FunctionComponent<BuildActionsType> = (initialBuild):JSX.Element => {

  const [artifact, setArtifact] = useState(initialBuild.build);

  const statusIcon= function (build: ArtifactListDTO) {
    if (build.succeeded) {
      return <Label color="green" icon={<CheckCircleIcon />}>
        Artifact Successful
      </Label>
    }
    return <Label color="red" icon={<ErrorCircleOIcon />}>
      Artifact Failed
    </Label>
  }

  return <Tr key={artifact.gav}>
    <Td>
      {statusIcon(artifact)}
    </Td>
    <Td dataLabel={columnNames.gav} modifier="truncate">
      {artifact.gav}
    </Td>
  </Tr>
}
export { ArtifactList };
