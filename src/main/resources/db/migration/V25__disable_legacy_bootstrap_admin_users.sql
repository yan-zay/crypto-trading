-- V5 historically inserted two enabled users with public default passwords. Editing V5 would
-- break Flyway checksums for installed databases, so disable only rows that still retain those
-- exact legacy hashes. Accounts whose credentials were already rotated are left unchanged.
UPDATE admin_user
SET enabled = 0
WHERE enabled = 1
  AND (
    (username = 'admin'
      AND password_hash = '$2a$10$G6TEcRNH0v7CUBBVehWvReTLMwx/9PhpNYS6/B3cP4Ffo.Dx5bFEK')
    OR
    (username = 'viewer'
      AND password_hash = '$2a$10$mfVXe5wdfp9bnpMy7nN8uudRir27.YlHPQAmNybfmyfCDwn0JElkC')
  );
