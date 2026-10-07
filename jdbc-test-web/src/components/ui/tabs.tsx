import { Tabs as Primitive } from "radix-ui";
export const Tabs = Primitive.Root;
export function TabsList({ children }: { children: React.ReactNode }) {
  return (
    <Primitive.List className="mb-4 flex w-fit gap-1 rounded-xl bg-secondary p-1">
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
      className="rounded-lg px-5 py-2 text-sm text-muted-foreground data-[state=active]:bg-white data-[state=active]:text-primary data-[state=active]:font-semibold data-[state=active]:shadow-sm"
    >
      {children}
    </Primitive.Trigger>
  );
}
export const TabsContent = Primitive.Content;
