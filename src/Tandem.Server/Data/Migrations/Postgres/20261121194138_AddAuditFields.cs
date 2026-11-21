using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Tandem.Server.Data.Migrations.Postgres
{
    /// <inheritdoc />
    public partial class AddAuditFields : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<string>(
                name: "EmulatorVersion",
                table: "SaveVersions",
                type: "character varying(40)",
                maxLength: 40,
                nullable: true);

            migrationBuilder.AddColumn<int>(
                name: "FileCount",
                table: "SaveVersions",
                type: "integer",
                nullable: true);

            migrationBuilder.AddColumn<DateTimeOffset>(
                name: "SilentNotifiedUtc",
                table: "Devices",
                type: "timestamp with time zone",
                nullable: true);

            migrationBuilder.AddColumn<bool>(
                name: "Partial",
                table: "BackupRuns",
                type: "boolean",
                nullable: false,
                defaultValue: false);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "EmulatorVersion",
                table: "SaveVersions");

            migrationBuilder.DropColumn(
                name: "FileCount",
                table: "SaveVersions");

            migrationBuilder.DropColumn(
                name: "SilentNotifiedUtc",
                table: "Devices");

            migrationBuilder.DropColumn(
                name: "Partial",
                table: "BackupRuns");
        }
    }
}
