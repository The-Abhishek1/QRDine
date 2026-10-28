"use client";

import { createClient, type SupabaseClient } from "@supabase/supabase-js";

let client: SupabaseClient | null = null;

export function customerSupabase(): SupabaseClient {
  const url = process.env.NEXT_PUBLIC_SUPABASE_URL;
  const key = process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY;
  if (!url || !key) throw new Error("Customer sign-in is not configured for this deployment.");
  client ??= createClient(url, key);
  return client;
}

export function hasCustomerRole(accessToken: string): boolean {
  try {
    const encoded = accessToken.split(".")[1]?.replace(/-/g, "+").replace(/_/g, "/");
    if (!encoded) return false;
    return JSON.parse(atob(encoded)).app_role === "customer";
  } catch {
    return false;
  }
}
