import TableApp from "@/components/TableApp";

export default async function TablePage({ params }: { params: Promise<{ token: string }> }) {
  const { token } = await params;
  return <TableApp token={token} />;
}
