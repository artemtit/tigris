export type CommandVariant = string | { posix: string; powershell: string };

export interface Step {
  title: string;
  body?: string;
  cmd?: CommandVariant;
  note?: string;
}

export interface FaqItem {
  q: string;
  a: string[];
  cmds?: { text: CommandVariant }[];
}
