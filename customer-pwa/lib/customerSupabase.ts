"use client";

import { createClient, type SupabaseClient } from "@supabase/supabase-js";

let client: SupabaseClient | null = null;

export function customerSupabase(): SupabaseClient {
  const functionsUrl = process.env.NEXT_PUBLIC_API_BASE;
  const url = process.env.NEXT_PUBLIC_SUPABASE_URL
    || functionsUrl?.replace(/\/functions\/v1\/?$/, "");
  const key = process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY;
  if (!url || !key) {
    const missing = [
      !url && "NEXT_PUBLIC_SUPABASE_URL",
      !key && "NEXT_PUBLIC_SUPABASE_ANON_KEY",
    ].filter(Boolean).join(" and ");
    throw new Error(
      `Customer sign-in is unavailable. Add ${missing} to the Vercel project’s Environment Variables, then redeploy.`,
    );
  }
  client ??= createClient(url, key);
  return client;
}

export function hasCustomerRole(accessToken: string): boolean {
  return readAppRole(accessToken) === "customer";
}

function readAppRole(accessToken: string): string | null {
  try {
    const encoded = accessToken.split(".")[1]?.replace(/-/g, "+").replace(/_/g, "/");
    if (!encoded) return null;
    const role = JSON.parse(atob(encoded)).app_role;
    return typeof role === "string" ? role : null;
  } catch {
    return null;
  }
}

export function isCustomerAccount(accessToken: string, userMetadata: Record<string, unknown> | undefined): boolean {
  const role = readAppRole(accessToken);
  return role === "customer" || (role === null && userMetadata?.account_type === "customer");
}
