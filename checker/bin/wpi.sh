#!/bin/bash

# This script performs whole-program inference on a project directory.

# For usage and requirements, see the "Whole-program inference"
# section of the Checker Framework manual:
# https://checkerframework.org/manual/#whole-program-inference

set -eo pipefail
# not set -u, because this script checks variables directly

while getopts "d:t:b:g:c:" opt; do
  case $opt in
    d)
      DIR="$OPTARG"
      ;;
    t)
      TIMEOUT="$OPTARG"
      ;;
    b)
      EXTRA_BUILD_ARGS="$OPTARG"
      ;;
    g)
      GRADLECACHEDIR="$OPTARG"
      ;;
    c)
      BUILD_TARGET="$OPTARG"
      ;;
    \?) # echo "Invalid option -$OPTARG" >&2
      ;;
  esac
done

# Make $@ be the arguments that should be passed to dljc.
shift $((OPTIND - 1))

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &> /dev/null && pwd)"
SCRIPT_NAME="$(basename "$0")"

# Report line numbers when the script fails, from
# https://unix.stackexchange.com/a/522815 .
trap 'echo >&2 "Error - exited with status $? at line $LINENO of ${SCRIPT_NAME}:";
         pr -tn "${SCRIPT_DIR}/${SCRIPT_NAME}" | tail -n+$((LINENO - 3)) | head -n7' ERR

echo "Starting $SCRIPT_NAME"

# check required arguments and environment variables:

if [ "${JAVA_HOME}" = "" ]; then
  has_java_home="no"
else
  has_java_home="yes"
fi
# shellcheck disable=SC2153 # testing for JAVA8_HOME, not a typo of JAVA_HOME
if [ "${JAVA8_HOME}" = "" ]; then
  has_java8="no"
else
  has_java8="yes"
fi
# shellcheck disable=SC2153 # testing for JAVA11_HOME, not a typo of JAVA_HOME
if [ "${JAVA11_HOME}" = "" ]; then
  has_java11="no"
else
  has_java11="yes"
fi
# shellcheck disable=SC2153 # testing for JAVA17_HOME, not a typo of JAVA_HOME
if [ "${JAVA17_HOME}" = "" ]; then
  has_java17="no"
else
  has_java17="yes"
fi
# shellcheck disable=SC2153 # testing for JAVA21_HOME, not a typo of JAVA_HOME
if [ "${JAVA21_HOME}" = "" ]; then
  has_java21="no"
else
  has_java21="yes"
fi
# shellcheck disable=SC2153 # testing for JAVA24_HOME, not a typo of JAVA_HOME
if [ "${JAVA24_HOME}" = "" ]; then
  has_java24="no"
else
  has_java24="yes"
fi
# shellcheck disable=SC2153 # testing for JAVA25_HOME, not a typo of JAVA_HOME
if [ "${JAVA25_HOME}" = "" ]; then
  has_java25="no"
else
  has_java25="yes"
fi

if [ "${has_java_home}" = "yes" ] && [ ! -d "${JAVA_HOME}" ]; then
  echo "JAVA_HOME is set to a non-existent directory ${JAVA_HOME}"
  exit 1
fi

if [ "${has_java_home}" = "yes" ]; then
  java_version=$("${JAVA_HOME}"/bin/java -version 2>&1 | head -1 | cut -d'"' -f2 | sed '/^1\./s///' | cut -d'.' -f1 | sed 's/-ea//')
  if [ "${has_java8}" = "no" ] && [ "${java_version}" = 8 ]; then
    export JAVA8_HOME="${JAVA_HOME}"
    has_java8="yes"
  fi
  if [ "${has_java11}" = "no" ] && [ "${java_version}" = 11 ]; then
    export JAVA11_HOME="${JAVA_HOME}"
    has_java11="yes"
  fi
  if [ "${has_java17}" = "no" ] && [ "${java_version}" = 17 ]; then
    export JAVA17_HOME="${JAVA_HOME}"
    has_java17="yes"
  fi
  if [ "${has_java21}" = "no" ] && [ "${java_version}" = 21 ]; then
    export JAVA21_HOME="${JAVA_HOME}"
    has_java21="yes"
  fi
  if [ "${has_java24}" = "no" ] && [ "${java_version}" = 24 ]; then
    export JAVA24_HOME="${JAVA_HOME}"
    has_java24="yes"
  fi
  if [ "${has_java25}" = "no" ] && [ "${java_version}" = 25 ]; then
    export JAVA25_HOME="${JAVA_HOME}"
    has_java25="yes"
  fi
fi

if [ "${has_java8}" = "yes" ] && [ ! -d "${JAVA8_HOME}" ]; then
  echo "JAVA8_HOME is set to a non-existent directory ${JAVA8_HOME}"
  exit 1
fi
if [ "${has_java11}" = "yes" ] && [ ! -d "${JAVA11_HOME}" ]; then
  echo "JAVA11_HOME is set to a non-existent directory ${JAVA11_HOME}"
  exit 1
fi
if [ "${has_java17}" = "yes" ] && [ ! -d "${JAVA17_HOME}" ]; then
  echo "JAVA17_HOME is set to a non-existent directory ${JAVA17_HOME}"
  exit 1
fi
if [ "${has_java21}" = "yes" ] && [ ! -d "${JAVA21_HOME}" ]; then
  echo "JAVA21_HOME is set to a non-existent directory ${JAVA21_HOME}"
  exit 1
fi
if [ "${has_java24}" = "yes" ] && [ ! -d "${JAVA24_HOME}" ]; then
  echo "JAVA24_HOME is set to a non-existent directory ${JAVA24_HOME}"
  exit 1
fi
if [ "${has_java25}" = "yes" ] && [ ! -d "${JAVA25_HOME}" ]; then
  echo "JAVA25_HOME is set to a non-existent directory ${JAVA25_HOME}"
  exit 1
fi

if [ "${has_java8}" = "no" ] && [ "${has_java11}" = "no" ] && [ "${has_java17}" = "no" ] && [ "${has_java21}" = "no" ] && [ "${has_java24}" = "no" ] && [ "${has_java25}" = "no" ]; then
  if [ "${has_java_home}" = "yes" ]; then
    echo "Cannot determine Java version from JAVA_HOME"
  else
    echo "No Java 8, 11, 17, 21, 24, or 25 JDKs found. At least one of JAVA_HOME, JAVA8_HOME, JAVA11_HOME, JAVA17_HOME, JAVA21_HOME, JAVA24_HOME, or JAVA25_HOME must be set."
  fi
  echo "JAVA_HOME = ${JAVA_HOME}"
  echo "JAVA8_HOME = ${JAVA8_HOME}"
  echo "JAVA11_HOME = ${JAVA11_HOME}"
  echo "JAVA17_HOME = ${JAVA17_HOME}"
  echo "JAVA21_HOME = ${JAVA21_HOME}"
  echo "JAVA24_HOME = ${JAVA24_HOME}"
  echo "JAVA25_HOME = ${JAVA25_HOME}"
  command -v java
  java -version
  exit 1
fi

if [ -z "${CHECKERFRAMEWORK}" ]; then
  echo "CHECKERFRAMEWORK is not set; it must be set to a locally-built Checker Framework. Please clone and build https://github.com/typetools/checker-framework"
  exit 1
fi

if [ ! -d "${CHECKERFRAMEWORK}" ]; then
  echo "CHECKERFRAMEWORK is set to a non-existent directory ${CHECKERFRAMEWORK}"
  exit 1
fi

if [ "${DIR}" = "" ]; then
  # echo "${SCRIPT_NAME}: no -d argument supplied, using the current directory."
  DIR=$(pwd)
fi

if [ ! -d "${DIR}" ]; then
  echo "${SCRIPT_NAME}'s -d argument was not a directory: ${DIR}"
  exit 1
fi

if [ "${EXTRA_BUILD_ARGS}" = "" ]; then
  EXTRA_BUILD_ARGS=""
fi

if [ "${GRADLECACHEDIR}" = "" ]; then
  # Assume that each project should use its own gradle cache. This is more expensive,
  # but prevents crashes on distributed file systems, such as the UW CSE machines.
  GRADLECACHEDIR=".gradle"
fi

function configure_and_exec_dljc {

  if [ -f build.gradle ]; then
    if [ "${BUILD_TARGET}" = "" ]; then
      BUILD_TARGET="compileJava"
    fi
    if [ -f gradlew ]; then
      chmod +x gradlew
      GRADLE_EXEC="./gradlew"
    else
      GRADLE_EXEC="gradle"
    fi
    if [ ! -d "${GRADLECACHEDIR}" ]; then
      mkdir "${GRADLECACHEDIR}"
    fi
    CLEAN_CMD="${GRADLE_EXEC} clean -g ${GRADLECACHEDIR} -Dorg.gradle.java.home=${JAVA21_HOME} ${EXTRA_BUILD_ARGS}"
    BUILD_CMD="${GRADLE_EXEC} clean ${BUILD_TARGET} -g ${GRADLECACHEDIR} -Dorg.gradle.java.home=${JAVA21_HOME} ${EXTRA_BUILD_ARGS}"
  elif [ -f pom.xml ]; then
    if [ "${BUILD_TARGET}" = "" ]; then
      BUILD_TARGET="compile"
    fi
    if [ -f mvnw ]; then
      chmod +x mvnw
      MVN_EXEC="./mvnw"
    else
      MVN_EXEC="mvn"
    fi
    # if running on Java 8, need /jre at the end of this Maven command
    if [ "${JAVA_HOME}" = "${JAVA8_HOME}" ]; then
      CLEAN_CMD="${MVN_EXEC} clean -Djava.home=${JAVA_HOME}/jre ${EXTRA_BUILD_ARGS}"
      BUILD_CMD="${MVN_EXEC} clean ${BUILD_TARGET} -Djava.home=${JAVA_HOME}/jre ${EXTRA_BUILD_ARGS}"
    else
      CLEAN_CMD="${MVN_EXEC} clean -Djava.home=${JAVA_HOME} ${EXTRA_BUILD_ARGS}"
      BUILD_CMD="${MVN_EXEC} clean ${BUILD_TARGET} -Djava.home=${JAVA_HOME} ${EXTRA_BUILD_ARGS}"
    fi
  elif [ -f build.xml ]; then
    # TODO: test these more thoroughly
    if [ "${BUILD_TARGET}" = "" ]; then
      BUILD_TARGET="compile"
    fi
    CLEAN_CMD="ant clean ${EXTRA_BUILD_ARGS}"
    BUILD_CMD="ant clean ${BUILD_TARGET} ${EXTRA_BUILD_ARGS}"
  else
    WPI_RESULTS_AVAILABLE="no build file found for ${REPO_NAME}; not calling DLJC"
    echo "${WPI_RESULTS_AVAILABLE}"
    return
  fi

  if [ "${JAVA_HOME}" = "${JAVA8_HOME}" ]; then
    JDK_VERSION_ARG="--jdkVersion 8"
  elif [ "${JAVA_HOME}" = "${JAVA11_HOME}" ]; then
    JDK_VERSION_ARG="--jdkVersion 11"
  elif [ "${JAVA_HOME}" = "${JAVA17_HOME}" ]; then
    JDK_VERSION_ARG="--jdkVersion 17"
  else
    # Default to the latest LTS release.  (Probably better to compute the version.)
    JDK_VERSION_ARG="--jdkVersion 11"
  fi

  # In bash 4.4, ${QUOTED_ARGS} below can be replaced by ${*@Q} .
  # (But, this script does not assume that bash is at least version 4.4.)
  QUOTED_ARGS=$(printf '%q ' "$@")

  # This command also includes "clean"; I'm not sure why it is necessary.
  DLJC_CMD="${DLJC} -t wpi ${JDK_VERSION_ARG} ${QUOTED_ARGS} -- ${BUILD_CMD}"

  if [ ! "${TIMEOUT}" = "" ]; then
    TMP="${DLJC_CMD}"
    DLJC_CMD="timeout ${TIMEOUT} ${TMP}"
  fi

  # Remove old DLJC output.
  rm -rf dljc-out
  mkdir -p "${DIR}/dljc-out/"

  # Ensure the project is clean before invoking DLJC.
  DLJC_CLEAN_STATUS=0
  CLEAN_OUTPUT_FILE=${DIR}/dljc-out/clean-output
  ## TODO: Why is this `eval` rather than just running the command?
  eval "${CLEAN_CMD} < /dev/null > ${CLEAN_OUTPUT_FILE}" 2>&1 || DLJC_CLEAN_STATUS=$?
  if [[ $DLJC_CLEAN_STATUS -ne 0 ]]; then
    WPI_RESULTS_AVAILABLE="dljc failed to clean with ${JDK_VERSION_ARG}"
    echo "${WPI_RESULTS_AVAILABLE}; see ${CLEAN_OUTPUT_FILE}"
    echo "---------------- Contents of ${DIR}/dljc-out: ----------------"
    ls -al "${DIR}/dljc-out"
    echo "---------------- End of contents of ${DIR}/dljc-out: ----------------"
    WPI_RESULTS_AVAILABLE="${WPI_RESULTS_AVAILABLE}"$'\n'"${CLEAN_CMD}"$'\n'"$(cat "${CLEAN_OUTPUT_FILE}")"
    return
  fi

  mkdir -p "${DIR}/dljc-out/"
  dljc_stdout=$(mktemp "${DIR}/dljc-out/dljc-stdout-$(date +%Y%m%d-%H%M%S)-XXX")

  PATH_BACKUP="${PATH}"
  export PATH="${JAVA_HOME}/bin:${PATH}"

  # shellcheck disable=SC2129 # recommended syntax was crashing mysteriously in CI
  echo "WORKING DIR: $(pwd)" >> "$dljc_stdout"
  echo "JAVA_HOME: ${JAVA_HOME}" >> "$dljc_stdout"
  echo "PATH: ${PATH}" >> "$dljc_stdout"
  echo "DLJC_CMD: ${DLJC_CMD}" >> "$dljc_stdout"
  DLJC_STATUS=0
  eval "${DLJC_CMD}" < /dev/null >> "$dljc_stdout" 2>&1 || DLJC_STATUS=$?

  export PATH="${PATH_BACKUP}"

  echo "==== Start of DLJC standard out/err (${dljc_stdout}) ===="
  cat "${dljc_stdout}"
  echo "==== End of DLJC standard out/err (${dljc_stdout}) ===="

  # The wpi.py script in do-like-javac outputs the following text if no build/whole-program-inference directory
  # exists, which means that WPI produced no output. When that happens, the reason is usually that the Checker
  # Framework crashed, so output the log file for easier debugging.
  wpi_no_output_message="No WPI outputs were discovered; it is likely that WPI failed or the Checker Framework crashed"
  if [[ $(cat "${dljc_stdout}") == *"${wpi_no_output_message}"* ]]; then
    wpi_log_path="${DIR}"/dljc-out/wpi-stdout.log
    echo "=== ${wpi_no_output_message}: start of ${wpi_log_path} ==="
    cat "${wpi_log_path}"
    echo "=== end of ${wpi_log_path} ==="
  fi

  if [[ $DLJC_STATUS -eq 124 ]]; then
    WPI_RESULTS_AVAILABLE="dljc timed out for ${DIR}"
    echo "${WPI_RESULTS_AVAILABLE}"
    return
  fi

  if [ -f dljc-out/wpi-stdout.log ]; then
    # Put, in file `typecheck.out`, everything from the last "Running ..." onwards.
    sed -n '/^Running/h;//!H;$!d;x;//p' dljc-out/wpi-stdout.log > dljc-out/typecheck.out
    WPI_RESULTS_AVAILABLE="yes"
    echo "dljc output is in ${DIR}/dljc-out/"
    echo "typecheck output is in ${DIR}/dljc-out/typecheck.out"
    echo "stdout is in $dljc_stdout"
  else
    WPI_RESULTS_AVAILABLE="dljc failed: file ${DIR}/dljc-out/wpi-stdout.log does not exist
dljc output is in ${DIR}/dljc-out/
stdout is in      $dljc_stdout"
    echo "${WPI_RESULTS_AVAILABLE}"
  fi
}

### Check and setup dependencies

# Clone or update DLJC
if [ "${DLJC}" = "" ]; then
  # The user did not set the DLJC environment variable.
  DLJC="${SCRIPT_DIR}/.do-like-javac/dljc"
  if [ ! -f "${DLJC}" ]; then
    (cd "$SCRIPT_DIR"/../.. && ./gradlew getDoLikeJavac)
  fi
else
  # The user did set the DLJC environment variable.
  if [ ! -f "${DLJC}" ]; then
    echo "Failure: DLJC is set to ${DLJC} which is not a file or does not exist."
    exit 1
  fi
fi
if [ ! -f "$SCRIPT_DIR/../dist/checker.jar" ]; then
  (cd "$SCRIPT_DIR"/../.. && ./gradlew assembleForJavac)
fi

### Main script

echo "Finished configuring ${SCRIPT_NAME}."

rm -f -- "${DIR}/.cannot-run-wpi"

cd "${DIR}" || exit 5

JAVA_HOME_BACKUP="${JAVA_HOME}"

# For the first run, use the Java versions in ascending priority order: 8 if
# it's available, otherwise 11, otherwise 17.
if [ "${has_java8}" = "yes" ]; then
  export JAVA_HOME="${JAVA8_HOME}"
elif [ "${has_java11}" = "yes" ]; then
  export JAVA_HOME="${JAVA11_HOME}"
elif [ "${has_java17}" = "yes" ]; then
  export JAVA_HOME="${JAVA17_HOME}"
fi
configure_and_exec_dljc "$@"
echo "First run configure_and_exec_dljc with JAVA_HOME=${JAVA_HOME}: WPI_RESULTS_AVAILABLE=${WPI_RESULTS_AVAILABLE}"

# If results aren't available after the first run, then re-run with Java 11 if
# it is available and the first run used Java 8 (since Java 8 has the highest priority,
# the first run using Java 8 is equivalent to Java 8 being available).
if [ "${WPI_RESULTS_AVAILABLE}" != "yes" ] && [ "${has_java11}" = "yes" ]; then
  if [ "${has_java8}" = "yes" ]; then
    export JAVA_HOME="${JAVA11_HOME}"
    echo "${SCRIPT_NAME} couldn't build using Java 8; trying Java 11"
    configure_and_exec_dljc "$@"
    echo "Second run configure_and_exec_dljc with JAVA_HOME=${JAVA_HOME}: WPI_RESULTS_AVAILABLE=${WPI_RESULTS_AVAILABLE}"
  fi
fi

# If results still aren't available, then re-run with Java 17 if it is available
# and the first run used Java 8 or Java 11 (since Java 17 has the lowest priority,
# the first run using Java 8 or Java 11 is equivalent to either of these being
# available).
if [ "${WPI_RESULTS_AVAILABLE}" != "yes" ] && [ "${has_java17}" = "yes" ]; then
  if [ "${has_java11}" = "yes" ] || [ "${has_java8}" = "yes" ]; then
    export JAVA_HOME="${JAVA17_HOME}"
    echo "${SCRIPT_NAME} couldn't build using Java 11 or Java 8; trying Java 17"
    configure_and_exec_dljc "$@"
    echo "Third run configure_and_exec_dljc with JAVA_HOME=${JAVA_HOME}: WPI_RESULTS_AVAILABLE=${WPI_RESULTS_AVAILABLE}"
  fi
fi

# support wpi-many.sh's ability to delete projects without usable results
# automatically
if [ "${WPI_RESULTS_AVAILABLE}" != "yes" ]; then
  echo "${SCRIPT_NAME}: dljc could not run the build successfully: ${WPI_RESULTS_AVAILABLE}"
  echo "Check the log files in ${DIR}/dljc-out/ for diagnostics."
  echo "${WPI_RESULTS_AVAILABLE}" > "${DIR}/.cannot-run-wpi"
fi

# reset JAVA_HOME to its initial value, which could be unset
if [ "${has_java_home}" = "yes" ]; then
  export JAVA_HOME="${JAVA_HOME_BACKUP}"
else
  unset JAVA_HOME
fi

echo "Exiting ${SCRIPT_NAME} successfully; pwd=$(pwd)"
