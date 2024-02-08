import * as React from 'react';
import {useEffect, useState} from 'react';
import {
  DataList,
  DataListCell,
  DataListContent,
  DataListItem,
  DataListItemCells,
  DataListItemRow,
  DataListToggle,
  Label, Pagination, Spinner,
  Title,
} from '@patternfly/react-core';
import {
  ImageDTO,
  ImageResourceService,
  IdentifiedDependencyDTO,
} from "../../services/openapi";
import {EmptyTable} from '@app/EmptyTable/EmptyTable';
import {
  AttentionBellIcon,ContainerNodeIcon,
  IceCreamIcon,
  InProgressIcon, ListIcon, OkIcon, OutlinedAngryIcon, RedhatIcon, StickyNoteIcon, WarningTriangleIcon
} from "@patternfly/react-icons";
import {Link, RouteComponentProps} from "react-router-dom";
import {BuildView} from "@app/BuildView/BuildView";
import {base64} from "../../services/openapi/core/request";


interface RouteParams {
  repo: string
}

interface ImageList extends RouteComponentProps<RouteParams> {
}


const ImageList: React.FunctionComponent<ImageList> = (props) => {

  const repo = props.match.params.repo
  const [images, setImages] = useState(Array<ImageDTO>);
  const [error, setError] = useState(false);
  const [state, setState] = useState('');

  const [count, setCount] = React.useState(0);
  const [page, setPage] = React.useState(1);
  const [perPage, setPerPage] = React.useState(20);

  useEffect(() => {
    setState('loading');
    ImageResourceService.getApiImage(repo,page, perPage).then()
      .then((res) => {
        console.log(res);
        setState('success');
        setImages(res.items);
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
      <DataList aria-label="Information">
      {images.map((image : ImageDTO, index) => (
          <ImageRow key={index} image={image}></ImageRow>
        ))}
        {images.length === 0 && <EmptyTable></EmptyTable>}
      </DataList>
    </React.Fragment>
  );
};

type ImageType = {
  image: ImageDTO,
};

const ImageRow: React.FunctionComponent<ImageType> = (image): JSX.Element => {

  const [imageExpanded, setImageExpanded] = React.useState(false);
  const [loaded, setLoaded] = React.useState(false);
  const [error, setError] = React.useState(false);
  const [fullImage, setFullImage] = React.useState(image.image);

  const toggleImages = () => {
    setImageExpanded(!imageExpanded);
    if (!loaded) {
      ImageResourceService.getApiImage1(image.image.digest, base64(image.image.repository))
        .then((s) => {setFullImage(s); setLoaded(true)}).catch()
    }
  };

  const health = function (image: ImageDTO) {
    if (!image.analysisComplete) {
      return <Label color="blue" icon={<InProgressIcon />}>
        Image Analysis in Progress
      </Label>
    }
    if (!loaded) {
      return <></>
    }
    const trusted = fullImage.totalDependencies - fullImage.untrustedDependencies

    if (fullImage.totalDependencies == 0) {
      return <Label color="blue" icon={<StickyNoteIcon />}>
        No Java
      </Label>
    }
    return <>
      {fullImage.untrustedDependencies > 0 && <Label color="red" icon={<WarningTriangleIcon />}>{fullImage.untrustedDependencies} Untrusted Dependencies</Label>}
      {trusted > 0 && <Label color="green" icon={<OkIcon />}>{trusted} Rebuilt Dependencies</Label>}
      {fullImage.availableBuilds > 0 && <Label color="orange" icon={<ListIcon />}>{fullImage.availableBuilds} Available Rebuilt Dependencies</Label>}

    </>
  }
  const dependencyRow = function (dep : IdentifiedDependencyDTO) {

    return <DataListItem>
      <DataListItemRow>
        <DataListItemCells
          dataListCells={[
            <DataListCell isIcon key="icon">
              {dep.source === 'rebuilt' && <OkIcon color={"green"}></OkIcon>}
              {dep.source === 'redhat' && <RedhatIcon color={"red"}></RedhatIcon>}
              {(dep.source !== 'redhat' && dep.source != 'rebuilt') && <WarningTriangleIcon color={"orange"}></WarningTriangleIcon>}
            </DataListCell>,
            <DataListCell key="primary content">
              {dep.dependencyBuildIdentifier != undefined && <Link to={`/builds/build/${dep.dependencyBuildIdentifier}`}>{dep.gav}</Link>}
              {dep.dependencyBuildIdentifier == undefined && <div id="gav">{dep.gav}</div>}
            </DataListCell>,
            <DataListCell key="primary content">
              {dep.inQueue && <Label color="blue" icon={<IceCreamIcon />}> In Build Queue</Label>}
              {(dep.buildAttemptId != null) && <Label color="green" icon={<OkIcon />}>Rebuilt Artifact</Label>}
              {(dep.buildAttemptId == null && dep.buildSuccess) && <Label color="orange" icon={<AttentionBellIcon />}>Rebuilt Artifact Available, Image Rebuild Required</Label>}
              {(dep.buildAttemptId == null && dep.dependencyBuildIdentifier != null && !dep.buildSuccess) && <Label color="red" icon={<OutlinedAngryIcon />}>Rebuild Failed</Label>}
              {(dep.buildAttemptId == null && dep.dependencyBuildIdentifier == null && !dep.buildSuccess) && <Label color="orange" icon={<OutlinedAngryIcon />}>Unknown Source</Label>}
            </DataListCell>,
          ]}
        />
      </DataListItemRow>
    </DataListItem>
  }

  return <DataListItem aria-labelledby="ex-item1" isExpanded={imageExpanded}>
    <DataListItemRow>
      {image.image.analysisComplete && <DataListToggle
        onClick={() => toggleImages()}
        isExpanded={imageExpanded}
        id="toggle"
        aria-controls="ex-expand"
      />}
      <DataListItemCells
        dataListCells={[
          <DataListCell isIcon key="icon">
            <ContainerNodeIcon/>
          </DataListCell>,
          <DataListCell key="primary content">
            <div id="ex-item1">{image.image.fullName}</div>
          </DataListCell>,
          <DataListCell key="health">
            {health(image.image)}
          </DataListCell>
        ]}
      />
    </DataListItemRow>
    <DataListContent
      aria-label="Image Details"
      id="ex-expand1"
      isHidden={!imageExpanded}
    >
      {loaded &&
        <DataList aria-label="Dependencies">
          {fullImage.dependencies?.map(d => (dependencyRow(d)))}
        </DataList>}
      {loaded || <Spinner></Spinner>}
      {error && <Label color="red" icon={<OutlinedAngryIcon />}>Error Loading Data</Label>}
    </DataListContent>
  </DataListItem>

}
export {ImageList};
