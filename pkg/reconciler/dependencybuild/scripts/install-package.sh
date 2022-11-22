#!/usr/bin/env sh
export PATH="$(workspaces.source.path)/packages:${PATH}"


wget --no-verbose --output-document=$(workspaces.source.path)/packages/{FILENAME} {URL} \
&& echo "${SHA256} $(workspaces.source.path)/packages/{FILENAME}" | sha256sum --check -

if [ "executable" = "{TYPE}" ]; then
  chmod +x $(workspaces.source.path)/packages/{FILENAME}
elif [  "tar" = "{TYPE}"  ]; then
  mkdir $(workspaces.source.path)/packages/{FILENAME}-extracted
  tar -xvf $(workspaces.source.path)/packages/{FILENAME} --directory $(workspaces.source.path)/packages/{FILENAME}-extracted
  export PATH="$(workspaces.source.path)/packages/{FILENAME}-extracted/{BINARY_PATH}:${PATH}"
else
    echo "Unknown Type {TYPE}"
    exit 1
fi

