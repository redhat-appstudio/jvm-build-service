import * as React from 'react';
import {useEffect, useState} from 'react';
import {Label, Spinner,} from '@patternfly/react-core';
import {DependencySetDTO, DependencySetResourceService, IdentifiedDependencyDTO,} from "../../services/openapi";
import {
  AttentionBellIcon, CopyIcon,
  IceCreamIcon,
  ListIcon,
  OkIcon,
  OutlinedAngryIcon,
  RedhatIcon,
  StickyNoteIcon,
  WarningTriangleIcon
} from "@patternfly/react-icons";
import {Link} from "react-router-dom";
import {Table, Tbody, Td, Tr} from "@patternfly/react-table";


type DependencySetType = {
  dependencySetId: number
};

const DependencySet: React.FunctionComponent<DependencySetType> = (props) => {

  if (props.dependencySetId == 0) {
    return <></>
  }

  const initial: DependencySetDTO = {
    availableBuilds: 0,
    totalDependencies: 0,
    trustedDependencies: 0,
    untrustedDependencies: 0
  }
  const [error, setError] = useState(false);
  const [state, setState] = useState('');
  const [image, setImage] = useState(initial);

  useEffect(() => {
    setState('loading');
    DependencySetResourceService.getApiDependencySet(props.dependencySetId).then()
      .then((res) => {
        console.log(res);
        setState('success');
        setImage(res)
      })
      .catch((err) => {
        console.error('Error:', err);
        setState('error');
        setError(err);
      });
  }, [props.dependencySetId]);

  if (state === 'error')
    return (
      <h1>
        {error.toString()}
      </h1>
    );
  if (state === 'loading')
    return <Spinner></Spinner>

  const health = function (depSet: DependencySetDTO) {
    const trusted = depSet.totalDependencies - depSet.untrustedDependencies

    if (depSet.totalDependencies == 0) {
      return <Label color="blue" icon={<StickyNoteIcon/>}>
        No Java Dependencies
      </Label>
    }
    return <>
      {depSet.untrustedDependencies > 0 &&
        <Label color="red" icon={<WarningTriangleIcon/>}>{depSet.untrustedDependencies} Untrusted Dependencies</Label>}
      {trusted > 0 && <Label color="green" icon={<OkIcon/>}>{trusted} Rebuilt Dependencies</Label>}
      {depSet.availableBuilds > 0 &&
        <Label color="orange" icon={<ListIcon/>}>{depSet.availableBuilds} Available Rebuilt Dependencies</Label>}

    </>
  }
  const dependencyRow = function (dep: IdentifiedDependencyDTO) {

    return <Tr>
      <Td key="icon">
        {dep.source === 'rebuilt' && <OkIcon color={"green"}></OkIcon>}
        {dep.source === 'redhat' && <RedhatIcon color={"red"}></RedhatIcon>}
        {(dep.source !== 'redhat' && dep.source != 'rebuilt') &&
          <WarningTriangleIcon color={"orange"}></WarningTriangleIcon>}
      </Td>
      <Td>
        {dep.dependencyBuildIdentifier != undefined &&
          <Link to={`/builds/build/${dep.dependencyBuildIdentifier}`}>{dep.gav}</Link>}
        {dep.dependencyBuildIdentifier == undefined && <div id="gav">{dep.gav}</div>}
      </Td>
      <Td>
        {dep.inQueue && <Label color="blue" icon={<IceCreamIcon/>}> In Build Queue</Label>}
        {(dep.buildAttemptId != null) && <Label color="green" icon={<OkIcon/>}>Rebuilt Artifact</Label>}
        {(dep.buildAttemptId == null && dep.buildSuccess) &&
          <Label color="orange" icon={<AttentionBellIcon/>}>Rebuilt Artifact Available, Image Rebuild Required</Label>}
        {(dep.buildAttemptId == null && dep.dependencyBuildIdentifier != null && !dep.buildSuccess) &&
          <Label color="red" icon={<OutlinedAngryIcon/>}>Rebuild Failed</Label>}
        {(dep.buildAttemptId == null && dep.dependencyBuildIdentifier == null && !dep.buildSuccess) &&
          <Label color="orange" icon={<OutlinedAngryIcon/>}>Unknown Source</Label>}
        {dep.shadedInto != null &&
          <Label color="blue" icon={<CopyIcon/>}>Shaded into {dep.shadedInto}</Label>}
      </Td>
    </Tr>
  }

  return <>
    <Table>
      <Tbody>
        <Tr><Td></Td><Td>Totals</Td><Td>{health(image)}</Td></Tr>
        {image.dependencies?.map(d => (dependencyRow(d)))}
      </Tbody>
    </Table></>
}
export {DependencySet};
