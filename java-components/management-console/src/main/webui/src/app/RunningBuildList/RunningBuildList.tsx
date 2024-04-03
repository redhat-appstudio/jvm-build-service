import * as React from 'react';
import {useEffect, useState} from 'react';
import {RunningBuildDTO, RunningBuildsResourceService} from "../../services/openapi";
import {EmptyTable} from '@app/EmptyTable/EmptyTable';
import {Table, Tbody, Td, Th, Thead, Tr} from "@patternfly/react-table";

const RunningBuildList: React.FunctionComponent = () => {
  const [builds, setBuilds] = useState(Array<RunningBuildDTO>);
  const [error, setError] = useState(false);
  const [state, setState] = useState('');


  useEffect(() => {
    setState('loading');
    RunningBuildsResourceService.getApiBuildsRunning().then()
      .then((res) => {
        console.log(res);
        setState('success');
        setBuilds(res);
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

  return (
    <React.Fragment>


      <Table aria-label="Running Build List">
        <Thead>
          <Tr>
            <Th width={10}>Build</Th>
            <Th width={10}>Status</Th>
            <Th width={10}>Start Time</Th>
          </Tr>
        </Thead>
        <Tbody>
          {builds.map((build, item) => (

            <Tr key={item}>
              <Td>{build.description}</Td>
              <Td>{build.status}</Td>
              <Td>{build.startTime}</Td>
            </Tr>
          ))}
          {builds.length === 0 && <EmptyTable></EmptyTable>}
        </Tbody>
      </Table>
    </React.Fragment>
  );
};

export {RunningBuildList};
