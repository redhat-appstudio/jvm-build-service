import * as React from 'react';
import {useEffect, useState} from 'react';
import {
  ActionListItem,
  Dropdown,
  DropdownItem,
  DropdownList,
  Label,
  MenuToggle,
  MenuToggleElement,
  Pagination, SearchInput,
  Toolbar,
  ToolbarContent,
  ToolbarItem,
} from '@patternfly/react-core';
import {Table, Tbody, Td, Th, Thead, Tr} from '@patternfly/react-table';
import {ArtifactEditResourceService, ArtifactHistoryResourceService, ArtifactListDTO} from "../../services/openapi";
import {CheckCircleIcon, EllipsisVIcon, ErrorCircleOIcon, WarningTriangleIcon} from "@patternfly/react-icons";
import {ArtifactEditModal} from "@app/ArtifactEditModal/ArtifactEditModal";
import {EmptyTable} from "@app/EmptyTable/EmptyTable";
import {Link} from "react-router-dom";


type StoredArtifactListType = {
  artifacts: Array<ArtifactListDTO>
  mavenRepo?: string
};

const columnNames = {
  status: 'Status',
  name: 'Artifact ID',
  gav: 'GAV',
  actions: 'Actions',
};

const StoredArtifactList: React.FunctionComponent<StoredArtifactListType> = (props) => {
  const builds = props.artifacts;

  const [dropDownOpen, setDropDownOpen] = useState(false);

  const [count, setCount] = React.useState(0);
  const [page, setPage] = React.useState(1);
  const [perPage, setPerPage] = React.useState(20);
  const emptyArtifact: ArtifactListDTO = {id: 0, gav: "", name: ""}
  const [artifact, setArtifact] = useState(emptyArtifact);
  const [modalOpen, setModalOpen] = useState(false);

  let transientGav = ''

  const editArtifact = (artifact: ArtifactListDTO) => {
    setArtifact(artifact)
    setModalOpen(true)
  }


  return (
    <React.Fragment>
      <ArtifactEditModal artifact={artifact} open={modalOpen} setOpen={setModalOpen}></ArtifactEditModal>
      <Table aria-label="Artifact List">
        <Thead>
          <Tr>
            <Th width={10}>{columnNames.status}</Th>
            <Th width={10}>{columnNames.name}
            </Th>
            <Th width={10}>{columnNames.gav}</Th>
            <Th width={10}>{columnNames.actions}</Th>
          </Tr>
        </Thead>
        <Tbody>
          {builds.length > 0 &&
            builds.sort((a, b) => a.gav.localeCompare(b.gav)).map((value: ArtifactListDTO, index) => (
              <ArtifactRow key={index} artifact={value} selectArtifact={editArtifact} mavenRepo={props.mavenRepo}></ArtifactRow>
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
  mavenRepo: string | undefined
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
    <Td dataLabel={columnNames.name} modifier="truncate">
      <Link to={`/artifacts/artifact/${artifact.artifact.name}`}>{artifact.artifact.name}</Link>
    </Td>
    <Td dataLabel={columnNames.gav} modifier="truncate">
      {artifact.mavenRepo == undefined ? artifact.artifact.gav : <a href={
        artifact.mavenRepo + (artifact.mavenRepo?.endsWith("/") ? '' : '/') +
        artifact.artifact.gav.split(":")[0].replace(/\./g, "/") +
        "/" +
        artifact.artifact.gav.split(":")[1] +
        "/" +
        artifact.artifact.gav.split(":")[2]
      } target="_blank">{artifact.artifact.gav}</a>}
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
export {StoredArtifactList};
