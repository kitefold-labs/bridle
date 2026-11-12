# Bridle shell integration for zsh 5.1+.
#
# Copyright 2026 Kitefold Labs. Licensed under the Apache License, Version 2.0.
#
# Marks prompts with OSC 133 (A prompt start, B input start, C command start, D;status
# command done) and reports the working directory with OSC 7, so Bridle can tell when the
# shell is waiting for a command and where it is. Inside tmux, each code is wrapped in tmux's
# passthrough sequence, and passthrough is turned on for this pane only.
#
# Source it at the end of ~/.zshrc, after anything else that sets PROMPT or prompt hooks.

[[ -o interactive ]] || return 0
(( ${+__bridle_si} )) && return 0
typeset -g __bridle_si=1

# Wraps an OSC body as the terminal will receive it; tmux unwraps passthrough itself.
__bridle_osc() {
  if [[ -n ${TMUX-} ]]; then
    printf '\ePtmux;\e\e]%s\a\e\\' "$1"
  else
    printf '\e]%s\a' "$1"
  fi
}

if [[ -n ${TMUX-} ]]; then
  # tmux 3.3+ drops passthrough unless this is on; older versions always pass it and reject the option.
  tmux set -p allow-passthrough on 2>/dev/null
  typeset -g __bridle_aid=";aid=${TMUX_PANE-}"
else
  typeset -g __bridle_aid=""
fi

# Prompt escapes: %{...%} marks zero-width text, and % must be doubled inside it.
typeset -g __bridle_B=$(__bridle_osc "133;B$__bridle_aid")
__bridle_B="%{${__bridle_B//\%/%%}%}"
typeset -g __bridle_prompted=""

# Percent-encodes a path for a file:// URL, byte by byte.
__bridle_urlencode() {
  setopt localoptions no_multibyte
  local s=$1 out="" c i
  for (( i = 1; i <= $#s; i++ )); do
    c=$s[i]
    case $c in
      [A-Za-z0-9/._~-]) out+=$c ;;
      *) out+="%${(l:2::0:)$(( [##16] #c ))}" ;;
    esac
  done
  print -rn -- $out
}

__bridle_precmd() {
  local exit_status=$?
  [[ -n $__bridle_prompted ]] && __bridle_osc "133;D;$exit_status$__bridle_aid"
  __bridle_prompted=1
  __bridle_osc "7;file://${HOST-}$(__bridle_urlencode $PWD)"
  __bridle_osc "133;A$__bridle_aid"
  return $exit_status
}

# Runs after every other precmd hook, which may have rewritten PROMPT, so it ends with B.
__bridle_prompt_end() {
  [[ $PROMPT == *"$__bridle_B"* ]] || PROMPT+=$__bridle_B
}

__bridle_preexec() {
  __bridle_osc "133;C$__bridle_aid"
}

autoload -Uz add-zsh-hook
precmd_functions=(__bridle_precmd ${precmd_functions[@]} __bridle_prompt_end)
add-zsh-hook preexec __bridle_preexec
