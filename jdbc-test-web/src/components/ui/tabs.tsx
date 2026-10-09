import { Tabs as Primitive } from "radix-ui";
export const Tabs = Primitive.Root;
export function TabsList({ children }: { children: React.ReactNode }) {
  return (
    <Primitive.List className="jdbc-tab-list">
      {children}
    </Primitive.List>
  );
}
export function TabsTrigger({
  value,
  children,
}: {
  value: string;
  children: React.ReactNode;
}) {
  return (
    <Primitive.Trigger
      value={value}
      className="jdbc-tab"
    >
      {children}
    </Primitive.Trigger>
  );
}
export const TabsContent = Primitive.Content;
