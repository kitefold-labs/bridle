# Bridle shell integration for bash 4.4+.
#
# Copyright 2026 Kitefold Labs. Licensed under the Apache License, Version 2.0.
#
# Marks prompts with OSC 133 (A prompt start, B input start, C command start, D;status
# command done) and reports the working directory with OSC 7, so Bridle can tell when the
# shell is waiting for a command and where it is. Inside tmux, each code is wrapped in tmux's
# passthrough sequence, and passthrough is turned on for this pane only.
#
# Source it at the end of ~/.bashrc, after anything else that sets PS1 or PROMPT_COMMAND.

[[ $- == *i* ]] || return 0
# PS0, which marks the start of a command, arrived in bash 4.4.
(( BASH_VERSINFO[0] > 4 || (BASH_VERSINFO[0] == 4 && BASH_VERSINFO[1] >= 4) )) || return 0
[[ -z ${__bridle_si-} ]] || return 0
__bridle_si=1

# Wraps an OSC body as the terminal will receive it; tmux unwraps passthrough itself.
__bridle_osc() {
  if [[ -n ${TMUX-} ]]; then
    # shellcheck disable=SC1003 # the trailing backslash is the second byte of ST (ESC \)
    printf '\ePtmux;\e\e]%s\a\e\\' "$1"
  else
    printf '\e]%s\a' "$1"
  fi
}

if [[ -n ${TMUX-} ]]; then
  # tmux 3.3+ drops passthrough unless this is on; older versions always pass it and reject the option.
  tmux set -p allow-passthrough on 2>/dev/null
  __bridle_aid=";aid=${TMUX_PANE-}"
else
  __bridle_aid=""
fi

# PS0 and PS1 treat backslashes as escapes, and tmux's terminator (ESC \) contains one.
__bridle_B=$(__bridle_osc "133;B$__bridle_aid")
__bridle_B=${__bridle_B//\\/\\\\}
__bridle_C=$(__bridle_osc "133;C$__bridle_aid")
__bridle_C=${__bridle_C//\\/\\\\}

# Percent-encodes a path for a file:// URL, byte by byte.
__bridle_urlencode() {
  local LC_ALL=C s=$1 out="" c i
  for ((i = 0; i < ${#s}; i++)); do
    c=${s:i:1}
    case $c in
      [A-Za-z0-9/._~-]) out+=$c ;;
      *) printf -v c '%%%02X' "'$c"; out+=$c ;;
    esac
  done
  printf '%s' "$out"
}

__bridle_prompted=""

# Runs first in PROMPT_COMMAND, so $? is still the last command's status. Returns it unchanged
# for the commands after it.
__bridle_precmd() {
  local status=$?
  [[ -n $__bridle_prompted ]] && __bridle_osc "133;D;$status$__bridle_aid"
  __bridle_prompted=1
  __bridle_osc "7;file://${HOSTNAME-}$(__bridle_urlencode "$PWD")"
  __bridle_osc "133;A$__bridle_aid"
  return "$status"
}

# Runs last in PROMPT_COMMAND, after anything that rewrites PS1, so the prompt ends with B.
__bridle_prompt_end() {
  local status=$?
  [[ $PS1 == *"$__bridle_B"* ]] || PS1+="\[$__bridle_B\]"
  [[ ${PS0-} == *"$__bridle_C"* ]] || PS0="${PS0-}$__bridle_C"
  return "$status"
}

if [[ $(declare -p PROMPT_COMMAND 2>/dev/null) == "declare -a"* ]]; then
  PROMPT_COMMAND=(__bridle_precmd "${PROMPT_COMMAND[@]}" __bridle_prompt_end)
else
  # Newlines, not semicolons: an existing value may already end in ";".
  # shellcheck disable=SC2128,SC2178 # this branch only runs when PROMPT_COMMAND is a string
  PROMPT_COMMAND="__bridle_precmd"$'\n'"${PROMPT_COMMAND:+$PROMPT_COMMAND$'\n'}__bridle_prompt_end"
fi
