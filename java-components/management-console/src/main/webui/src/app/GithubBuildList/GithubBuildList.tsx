import * as React from 'react';
import {useEffect, useState} from 'react';
import {Pagination, Toolbar, ToolbarContent, ToolbarItem,} from '@patternfly/react-core';
import {GithubBuildDTO, GithubBuildsResourceService} from "../../services/openapi";
import {EmptyTable} from '@app/EmptyTable/EmptyTable';
import {Table, Tbody, Td, Thead, Tr} from "@patternfly/react-table";
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
      {builds.length === 0 && <EmptyTable></EmptyTable>}
      {builds.length > 0 && <Table>
        <Thead>
          <Tr><Td>Build</Td></Tr>
        </Thead>
        <Tbody>
          {builds.map((build: GithubBuildDTO, index) => (
            <Tr key={index}><Td><Link to={`/builds/github/build/${build.id}`}>{build.name}</Link></Td></Tr>
          ))}
        </Tbody>
      </Table>
      }
    </React.Fragment>
  );
};

export {GithubBuildList};
