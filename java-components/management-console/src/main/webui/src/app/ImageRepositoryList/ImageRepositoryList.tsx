import * as React from 'react';
import {useEffect, useState} from 'react';
import {Pagination, Toolbar, ToolbarContent, ToolbarItem,} from '@patternfly/react-core';
import {ImageRepositoryResourceService,} from "../../services/openapi";
import {EmptyTable} from '@app/EmptyTable/EmptyTable';
import {Link} from "react-router-dom";
import {Table, Td, Th, Thead, Tr} from "@patternfly/react-table";
import {base64} from "../../services/openapi/core/request";

const ImageRepositoryList: React.FunctionComponent = () => {
  const [repositories, setRepositories] = useState(Array<string>);
  const [error, setError] = useState(false);
  const [state, setState] = useState('');

  const [count, setCount] = React.useState(0);
  const [page, setPage] = React.useState(1);
  const [perPage, setPerPage] = React.useState(20);

  useEffect(() => {
    setState('loading');
    ImageRepositoryResourceService.getApiImageRepository(page, perPage).then()
      .then((res) => {
        console.log(res);
        setState('success');
        setRepositories(res.items);
        setCount(res.count)
        if (res.perPage != perPage) {
          setPerPage(res.perPage)
        }
        if (res.pageNo != page) {
          setPage(res.pageNo)
        }
      })
      .catch((err) => {
        console.error('Error:', err);
        setState('error');
        setError(err);
      });
  }, [page, perPage]);

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
  return (
    <React.Fragment>
      <Toolbar>
        <ToolbarContent>
          <ToolbarItem variant="pagination">
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
          </ToolbarItem>
        </ToolbarContent>
      </Toolbar>
      <Table>
        <Thead>
          <Th>Image Repository</Th>
        </Thead>

        {repositories.map((image: string) => {
          return <Tr key={image}><Td><Link to={`/images/repository/${base64(image)}`}>{image}</Link></Td></Tr>
        })}
        {repositories.length === 0 && <EmptyTable></EmptyTable>}
      </Table>
    </React.Fragment>
  );
};

export {ImageRepositoryList};
