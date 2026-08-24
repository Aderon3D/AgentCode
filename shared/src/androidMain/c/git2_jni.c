#include <jni.h>
#include <git2.h>
#include <string.h>

// ponytail: thin JNI shim — each function maps 1:1 to a libgit2 call.
// No caching of git_repository* across calls (stateless, matches the
// ProcessRunner contract). Errors come back as a non-null jstring.

JNIEXPORT jstring JNICALL
Java_com_agent_code_workspace_LibGit2Backend_nativeInit(JNIEnv *env, jobject thiz) {
    int err = git_libgit2_init();
    return err < 0 ? (*env)->NewStringUTF(env, "git_libgit2_init failed") : NULL;
}

JNIEXPORT jstring JNICALL
Java_com_agent_code_workspace_LibGit2Backend_nativeShutdown(JNIEnv *env, jobject thiz) {
    git_libgit2_shutdown();
    return NULL;
}

JNIEXPORT jstring JNICALL
Java_com_agent_code_workspace_LibGit2Backend_nativeWorktreeAdd(
    JNIEnv *env, jobject thiz,
    jstring jrepo, jstring jname, jstring jpath, jstring jbase) {
    const char *repo = (*env)->GetStringUTFChars(env, jrepo, NULL);
    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    const char *base = (*env)->GetStringUTFChars(env, jbase, NULL);

    git_repository *r = NULL;
    git_reference *branch_ref = NULL;
    git_annotated_commit *head = NULL;
    int err;
    jstring result = NULL;

    err = git_repository_open_ext(&r, repo, 0, NULL);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    // Create orphan branch at HEAD
    git_oid head_oid;
    err = git_reference_name_to_id(&head_oid, r, "HEAD");
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    err = git_branch_create(&branch_ref, r, name, 0 /* not force */, NULL);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    // Create worktree
    git_worktree *wt = NULL;
    git_buf buf = GIT_BUF_INIT;
    err = git_worktree_add(&wt, r, name, path, NULL);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    git_worktree_free(wt);

out:
    if (branch_ref) git_reference_free(branch_ref);
    if (r) git_repository_free(r);
    (*env)->ReleaseStringUTFChars(env, jrepo, repo);
    (*env)->ReleaseStringUTFChars(env, jname, name);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    (*env)->ReleaseStringUTFChars(env, jbase, base);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_agent_code_workspace_LibGit2Backend_nativeWorktreeRemove(
    JNIEnv *env, jobject thiz,
    jstring jrepo, jstring jname) {
    const char *repo = (*env)->GetStringUTFChars(env, jrepo, NULL);
    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);

    git_repository *r = NULL;
    git_worktree *wt = NULL;
    jstring result = NULL;
    int err;

    err = git_repository_open_ext(&r, repo, 0, NULL);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    err = git_worktree_lookup(&wt, r, name);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    err = git_worktree_remove(wt, 1 /* force */);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

out:
    if (wt) git_worktree_free(wt);
    if (r) git_repository_free(r);
    (*env)->ReleaseStringUTFChars(env, jrepo, repo);
    (*env)->ReleaseStringUTFChars(env, jname, name);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_agent_code_workspace_LibGit2Backend_nativeCheckout(
    JNIEnv *env, jobject thiz,
    jstring jrepo, jstring jbranch) {
    const char *repo = (*env)->GetStringUTFChars(env, jrepo, NULL);
    const char *branch = (*env)->GetStringUTFChars(env, jbranch, NULL);

    git_repository *r = NULL;
    git_reference *ref = NULL;
    git_object *target = NULL;
    jstring result = NULL;
    int err;

    err = git_repository_open_ext(&r, repo, 0, NULL);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    err = git_reference_lookup(&ref, r, branch);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    err = git_reference_peel(&target, ref, GIT_OBJECT_COMMIT);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    err = git_checkout_tree(r, target, NULL, NULL);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    // Update HEAD
    err = git_repository_set_head(r, branch);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

out:
    if (target) git_object_free(target);
    if (ref) git_reference_free(ref);
    if (r) git_repository_free(r);
    (*env)->ReleaseStringUTFChars(env, jrepo, repo);
    (*env)->ReleaseStringUTFChars(env, jbranch, branch);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_agent_code_workspace_LibGit2Backend_nativeMergeSquash(
    JNIEnv *env, jobject thiz,
    jstring jrepo, jstring jbranch) {
    const char *repo = (*env)->GetStringUTFChars(env, jrepo, NULL);
    const char *branch = (*env)->GetStringUTFChars(env, jbranch, NULL);

    git_repository *r = NULL;
    git_reference *branch_ref = NULL;
    git_annotated_commit *their = NULL;
    git_merge_options merge_opts = GIT_MERGE_OPTIONS_INIT;
    git_checkout_options checkout_opts = GIT_CHECKOUT_OPTIONS_INIT;
    jstring result = NULL;
    int err;

    err = git_repository_open_ext(&r, repo, 0, NULL);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    // Resolve branch to annotated commit
    err = git_reference_lookup(&branch_ref, r, branch);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    err = git_annotated_commit_from_ref(&their, r, branch_ref);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    // Merge
    merge_opts.flags = GIT_MERGE_FIND_RENAMES;
    checkout_opts.checkout_strategy = GIT_CHECKOUT_SAFE;
    err = git_merge(r, (const git_annotated_commit **)&their, 1, &merge_opts, &checkout_opts);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

out:
    if (their) git_annotated_commit_free(their);
    if (branch_ref) git_reference_free(branch_ref);
    if (r) git_repository_free(r);
    (*env)->ReleaseStringUTFChars(env, jrepo, repo);
    (*env)->ReleaseStringUTFChars(env, jbranch, branch);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_agent_code_workspace_LibGit2Backend_nativeAddAll(
    JNIEnv *env, jobject thiz,
    jstring jrepo) {
    const char *repo = (*env)->GetStringUTFChars(env, jrepo, NULL);
    git_repository *r = NULL;
    git_index *idx = NULL;
    jstring result = NULL;
    int err;

    err = git_repository_open_ext(&r, repo, 0, NULL);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    err = git_repository_index(&idx, r);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    err = git_index_add_all(idx, NULL, 0, NULL, NULL);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    err = git_index_write(idx);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

out:
    if (idx) git_index_free(idx);
    if (r) git_repository_free(r);
    (*env)->ReleaseStringUTFChars(env, jrepo, repo);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_agent_code_workspace_LibGit2Backend_nativeCommit(
    JNIEnv *env, jobject thiz,
    jstring jrepo, jstring jmessage) {
    const char *repo = (*env)->GetStringUTFChars(env, jrepo, NULL);
    const char *message = (*env)->GetStringUTFChars(env, jmessage, NULL);

    git_repository *r = NULL;
    git_index *idx = NULL;
    git_oid tree_oid, commit_oid, parent_oid;
    git_tree *tree = NULL;
    git_reference *head_ref = NULL;
    git_commit *parent = NULL;
    jstring result = NULL;
    int err;

    err = git_repository_open_ext(&r, repo, 0, NULL);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    // Write index to tree
    err = git_repository_index(&idx, r);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    err = git_index_write_tree(&tree_oid, idx);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    err = git_tree_lookup(&tree, r, &tree_oid);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    // Get parent commit (may fail if empty repo)
    err = git_reference_lookup(&head_ref, r, "HEAD");
    if (err == 0) {
        git_reference_name_to_id(&parent_oid, r, "HEAD");
        err = git_commit_lookup(&parent, r, &parent_oid);
    }

    // Create commit
    git_signature *sig = NULL;
    err = git_signature_now(&sig, "AgentCode", "agent@code.local");
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    err = git_commit_create(&commit_oid, r, "HEAD", sig, sig, NULL, message, tree, parent ? 1 : 0, parent ? (const git_commit **)&parent : NULL);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

out:
    if (sig) git_signature_free(sig);
    if (parent) git_commit_free(parent);
    if (head_ref) git_reference_free(head_ref);
    if (tree) git_tree_free(tree);
    if (idx) git_index_free(idx);
    if (r) git_repository_free(r);
    (*env)->ReleaseStringUTFChars(env, jrepo, repo);
    (*env)->ReleaseStringUTFChars(env, jmessage, message);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_agent_code_workspace_LibGit2Backend_nativeBranchDelete(
    JNIEnv *env, jobject thiz,
    jstring jrepo, jstring jname) {
    const char *repo = (*env)->GetStringUTFChars(env, jrepo, NULL);
    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);

    git_repository *r = NULL;
    git_reference *ref = NULL;
    jstring result = NULL;
    int err;

    err = git_repository_open_ext(&r, repo, 0, NULL);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    char refname[256];
    snprintf(refname, sizeof(refname), "refs/heads/%s", name);

    err = git_reference_lookup(&ref, r, refname);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    err = git_reference_delete(ref);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

out:
    if (ref) git_reference_free(ref);
    if (r) git_repository_free(r);
    (*env)->ReleaseStringUTFChars(env, jrepo, repo);
    (*env)->ReleaseStringUTFChars(env, jname, name);
    return result;
}
